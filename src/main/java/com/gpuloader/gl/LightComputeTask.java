package com.gpuloader.gl;

import com.gpuloader.core.GPUTask;
import com.mojang.logging.LogUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/**
 * GPU Compute Shader を用いてチャンク内のブロック光・空光を一括伝播するタスク。
 * セル・オートマトン（Jacobi反復法）により、CPUのBFSキュー処理を完全にGPUへオフロードします。
 */
public class LightComputeTask extends GPUTask<LightComputeTask.LightResult> {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static class LightResult {
        public final byte[][] blockLightSections;
        public final byte[][] skyLightSections;

        public LightResult(byte[][] blockLightSections, byte[][] skyLightSections) {
            this.blockLightSections = blockLightSections;
            this.skyLightSections = skyLightSections;
        }
    }

    private final int[] blockData;
    private final int sectionCount;
    private final int height;
    private final int gridX = 18;
    private final int gridZ = 18;
    private final int gridY;
    private final int totalVoxels;

    // Static GPU resource cache
    private static int program = -1;
    private static int locGridX = -1;
    private static int locGridY = -1;
    private static int locGridZ = -1;
    private static int locPassType = -1;

    private static int blockDataSsbo = -1;
    private static int lightASsbo = -1;
    private static int lightBSsbo = -1;
    private static int allocatedVoxelCapacity = 0;

    private static ByteBuffer readbackBuffer = null;

    public LightComputeTask(int[] blockData, int sectionCount) {
        this.blockData = blockData;
        this.sectionCount = sectionCount;
        this.height = sectionCount * 16;
        this.gridY = this.height + 2;
        this.totalVoxels = gridX * gridZ * gridY;
    }

    private static synchronized void initShader() {
        if (program != -1) return;

        try {
            InputStream is = LightComputeTask.class.getResourceAsStream("/assets/gpuloader/shaders/light_propagation.comp");
            if (is == null) {
                throw new RuntimeException("Could not find light_propagation.comp");
            }
            String source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            program = GLShaderManager.createComputeProgram("gpuloader:light_propagation", source);

            if (program != -1) {
                locGridX = GL20.glGetUniformLocation(program, "grid_x");
                locGridY = GL20.glGetUniformLocation(program, "grid_y");
                locGridZ = GL20.glGetUniformLocation(program, "grid_z");
                locPassType = GL20.glGetUniformLocation(program, "pass_type");
                LOGGER.info("[GPU Light] Compute shader initialized successfully. Program: {}", program);
            } else {
                LOGGER.error("[GPU Light] Failed to create compute shader program");
            }
        } catch (Exception e) {
            LOGGER.error("[GPU Light] Failed to load light_propagation.comp", e);
        }
    }

    private static synchronized void ensureBuffers(int requiredVoxels) {
        if (allocatedVoxelCapacity >= requiredVoxels && blockDataSsbo != -1) {
            return;
        }

        if (blockDataSsbo != -1) GL15.glDeleteBuffers(blockDataSsbo);
        if (lightASsbo != -1) GL15.glDeleteBuffers(lightASsbo);
        if (lightBSsbo != -1) GL15.glDeleteBuffers(lightBSsbo);

        int bufferSizeBytes = requiredVoxels * 4; // 1 int per voxel

        blockDataSsbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSsbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bufferSizeBytes, GL15.GL_DYNAMIC_DRAW);

        lightASsbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, lightASsbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bufferSizeBytes, GL15.GL_DYNAMIC_DRAW);

        lightBSsbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, lightBSsbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bufferSizeBytes, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        readbackBuffer = BufferUtils.createByteBuffer(bufferSizeBytes);
        allocatedVoxelCapacity = requiredVoxels;

        LOGGER.info("[GPU Light] Allocated SSBO buffers for {} voxels ({} KB each)", requiredVoxels, bufferSizeBytes / 1024);
    }

    @Override
    public void execute() {
        if (!GLShaderManager.isSupported()) {
            result.completeExceptionally(new UnsupportedOperationException("Compute Shaders not supported"));
            return;
        }

        initShader();
        if (program == -1) {
            result.completeExceptionally(new IllegalStateException("Light compute shader not compiled"));
            return;
        }

        ensureBuffers(totalVoxels);

        // Upload block data
        IntBuffer uploadBuffer = BufferUtils.createIntBuffer(totalVoxels);
        uploadBuffer.put(blockData, 0, totalVoxels);
        uploadBuffer.flip();

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blockDataSsbo);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, uploadBuffer);

        GL20.glUseProgram(program);
        GL20.glUniform1i(locGridX, gridX);
        GL20.glUniform1i(locGridY, gridY);
        GL20.glUniform1i(locGridZ, gridZ);

        // Workgroup dispatch sizes: local_size is (8, 4, 8)
        int wx = (gridX + 7) / 8; // 3
        int wy = (gridY + 3) / 4; // ~97
        int wz = (gridZ + 7) / 8; // 3

        // Bind BlockData to binding 0
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, blockDataSsbo);

        // ==========================================
        // 1. Block Light Computation
        // ==========================================
        // Pass 0: Init block light (write to lightASsbo)
        GL20.glUniform1i(locPassType, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, lightASsbo);
        GL43.glDispatchCompute(wx, wy, wz);
        GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // Pass 1: Spread block light (15 Jacobi steps)
        int currentInSsbo = lightASsbo;
        int currentOutSsbo = lightBSsbo;
        GL20.glUniform1i(locPassType, 1);

        for (int i = 0; i < 15; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, currentInSsbo);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, currentOutSsbo);
            GL43.glDispatchCompute(wx, wy, wz);
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            // Swap ping-pong
            int temp = currentInSsbo;
            currentInSsbo = currentOutSsbo;
            currentOutSsbo = temp;
        }

        // Readback Block Light
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, currentInSsbo);
        readbackBuffer.clear();
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, readbackBuffer);
        IntBuffer blockLightInts = readbackBuffer.asIntBuffer();
        byte[][] blockSections = extractSections(blockLightInts);

        // ==========================================
        // 2. Sky Light Computation
        // ==========================================
        // Pass 2: Init sky light (vertical sun ray descent, wy = 1 for y=0 threads)
        GL20.glUniform1i(locPassType, 2);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, lightASsbo);
        GL43.glDispatchCompute(wx, 1, wz);
        GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // Pass 3: Spread sky light (15 Jacobi steps)
        currentInSsbo = lightASsbo;
        currentOutSsbo = lightBSsbo;
        GL20.glUniform1i(locPassType, 3);

        for (int i = 0; i < 15; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, currentInSsbo);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, currentOutSsbo);
            GL43.glDispatchCompute(wx, wy, wz);
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            // Swap ping-pong
            int temp = currentInSsbo;
            currentInSsbo = currentOutSsbo;
            currentOutSsbo = temp;
        }

        // Readback Sky Light
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, currentInSsbo);
        readbackBuffer.clear();
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, readbackBuffer);
        IntBuffer skyLightInts = readbackBuffer.asIntBuffer();
        byte[][] skySections = extractSections(skyLightInts);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL20.glUseProgram(0);

        result.complete(new LightResult(blockSections, skySections));
    }

    /**
     * GPUで計算されたグリッド (18x(height+2)x18) から、
     * チャンク内部 (16xheightx16) のボクセルを取り出してバニラの DataLayer 形式 (各セクション 2048 バイト) にパックする。
     */
    private byte[][] extractSections(IntBuffer lightInts) {
        byte[][] sections = new byte[sectionCount][2048];

        for (int s = 0; s < sectionCount; s++) {
            byte[] sectionData = sections[s];
            int sectionBaseY = s * 16;

            for (int y = 0; y < 16; y++) {
                int worldY = sectionBaseY + y;
                int paddedY = worldY + 1; // y=0 in chunk is y=1 in padded grid

                for (int z = 0; z < 16; z++) {
                    int paddedZ = z + 1;

                    for (int x = 0; x < 16; x++) {
                        int paddedX = x + 1;
                        int voxelIdx = (paddedY * gridX * gridZ) + (paddedZ * gridX) + paddedX;
                        int lightLevel = lightInts.get(voxelIdx) & 0x0F;

                        // Vanilla DataLayer packing:
                        // Index is (y << 8) | (z << 4) | x;
                        // Byte index is Index >> 1;
                        // Even x uses lower 4 bits, odd x uses upper 4 bits.
                        int blockIndex = (y << 8) | (z << 4) | x;
                        int byteIndex = blockIndex >> 1;

                        if ((blockIndex & 1) == 0) {
                            sectionData[byteIndex] |= (byte) (lightLevel & 0x0F);
                        } else {
                            sectionData[byteIndex] |= (byte) ((lightLevel & 0x0F) << 4);
                        }
                    }
                }
            }
        }
        return sections;
    }
}
