package com.gpuloader.gl;

import com.gpuloader.core.BufferPool;
import com.gpuloader.core.GPUTask;
import com.gpuloader.core.MeshCullManager;
import com.mojang.logging.LogUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GPU Compute Shader でチャンクセクション（16x16x16）のフェイスカリングマスクを計算するタスク。
 * PBO非同期読み取り方式により、描画スレッドをブロックせずに実行可能。
 */
public class MeshCullTask extends GPUTask<int[]> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BLOCK_COUNT = 4096;
    private static final int OPACITY_UINT_COUNT = 128;

    private static class PendingMeshBatch {
        final long sectionKey;
        final PBOReadbackBuffer pbo;

        PendingMeshBatch(long sectionKey, PBOReadbackBuffer pbo) {
            this.sectionKey = sectionKey;
            this.pbo = pbo;
        }
    }

    private static final Queue<PendingMeshBatch> PENDING_QUEUE = new ConcurrentLinkedQueue<>();
    private static final List<PBOReadbackBuffer> PBO_POOL = new ArrayList<>();
    private static final int POOL_SIZE = 32;

    private static int meshProgram = -1;
    private static int inputBuffer = -1;
    private static int staticCounter = 0;

    private final long sectionKey;
    private final int[] opacityFlags;

    public MeshCullTask(long sectionKey, int[] opacityFlags) {
        this.sectionKey = sectionKey;
        this.opacityFlags = opacityFlags;
    }

    @Override
    public void execute() {
        try {
            if (meshProgram == -1) {
                meshProgram = GLShaderManager.createComputeProgram("mesh",
                        GLShaderManager.loadShaderSource("/assets/gpuloader/shaders/mesh.comp"));
                if (meshProgram == -1) return;
            }

            processReadyBatches();

            int requiredBytes = BLOCK_COUNT * Integer.BYTES;
            PBOReadbackBuffer pbo = getAvailablePBO(requiredBytes);

            if (pbo == null) {
                return;
            }

            GL20.glUseProgram(meshProgram);
            GL20.glUniform1i(GL20.glGetUniformLocation(meshProgram, "section_size"), 16);

            // Binding 0: Opacity Flags
            if (inputBuffer == -1) {
                inputBuffer = GL15.glGenBuffers();
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, inputBuffer);
            IntBuffer opacityBuf = BufferUtils.createIntBuffer(OPACITY_UINT_COUNT);
            opacityBuf.put(opacityFlags, 0, OPACITY_UINT_COUNT).flip();
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, opacityBuf, GL15.GL_STREAM_DRAW);
            GL32.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, inputBuffer);

            // Binding 1: PBO Write
            pbo.bindWriteBuffer(1);

            GL43.glDispatchCompute(2, 2, 2);

            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            pbo.insertFence();
            pbo.swap();

            PENDING_QUEUE.add(new PendingMeshBatch(sectionKey, pbo));
        } finally {
            // 入力バッファをプールに必ず返却（メモリリーク防止）
            BufferPool.releaseOpacityBuffer(opacityFlags);
        }
    }

    public static void processReadyBatches() {
        int queueSize = PENDING_QUEUE.size();
        for (int i = 0; i < queueSize; i++) {
            PendingMeshBatch pending = PENDING_QUEUE.peek();
            if (pending == null) break;

            ByteBuffer mapped = pending.pbo.tryRead(0L);
            if (mapped != null) {
                PENDING_QUEUE.poll();
                IntBuffer resultBuf = mapped.asIntBuffer();
                int[] faceMasks = BufferPool.acquireMeshBuffer();
                resultBuf.get(faceMasks);

                pending.pbo.finishRead();
                MeshCullManager.storeCullResult(pending.sectionKey, faceMasks);
            } else {
                break;
            }
        }
    }

    private synchronized static PBOReadbackBuffer getAvailablePBO(int requiredBytes) {
        Set<PBOReadbackBuffer> inUse = new HashSet<>();
        for (PendingMeshBatch b : PENDING_QUEUE) inUse.add(b.pbo);

        for (PBOReadbackBuffer pbo : PBO_POOL) {
            if (!inUse.contains(pbo) && pbo.getCapacity() >= requiredBytes) return pbo;
        }

        if (PBO_POOL.size() < POOL_SIZE) {
            PBOReadbackBuffer pbo = new PBOReadbackBuffer(requiredBytes);
            pbo.init();
            PBO_POOL.add(pbo);
            return pbo;
        }
        return null;
    }

    public static void cleanup() {
        for (PBOReadbackBuffer p : PBO_POOL) p.cleanup();
        PBO_POOL.clear();
        PENDING_QUEUE.clear();
        if (inputBuffer != -1) {
            GL15.glDeleteBuffers(inputBuffer);
            inputBuffer = -1;
        }
        meshProgram = -1;
    }
}
