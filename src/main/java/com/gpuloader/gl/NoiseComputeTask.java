package com.gpuloader.gl;

import com.gpuloader.core.GPUTask;
import com.gpuloader.core.NoiseBatchManager.NoiseRequest;
import com.mojang.logging.LogUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GPU Compute Shaderで多層ノイズを計算するタスク。
 * Persistent Mapping により、CPU-GPU同期の足止めを排除。
 */
public class NoiseComputeTask extends GPUTask<Void> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int batchSize;
    private final int height;
    private final List<NoiseRequest> batchRequests;
    private final int[] permutation;
    private final int width = 5, depth = 5;
    private final int channels = 7; // Density + 6 Climate params

    private static class PendingBatch {
        final List<NoiseRequest> requests;
        final PBOReadbackBuffer pbo;
        final int height;
        final long submitTime;

        PendingBatch(List<NoiseRequest> requests, PBOReadbackBuffer pbo, int height, long submitTime) {
            this.requests = requests;
            this.pbo = pbo;
            this.height = height;
            this.submitTime = submitTime;
        }
    }

    private static final Queue<PendingBatch> PENDING_QUEUE = new ConcurrentLinkedQueue<>();
    private static final List<PBOReadbackBuffer> PBO_POOL = new ArrayList<>();
    private static final int POOL_SIZE = 32;

    private static int permBuffer = -1;
    private static int noiseProgram = -1;
    
    // Uniform locations cache
    private static int loc_request_x = -1;
    private static int loc_request_z = -1;
    private static int loc_width = -1;
    private static int loc_height = -1;
    private static int loc_depth = -1;
    private static int loc_batch_size = -1;
    private static int loc_enable_biome = -1;

    private static int terrainBatchCount = 0;
    private static long terrainTotalLatency = 0;
    private static int terrainTotalChunks = 0;

    private static long terrainLastLogTime = 0;
    private static int terrain10mBatchCount = 0;
    private static long terrain10mTotalLatency = 0;
    private static int terrain10mTotalChunks = 0;

    public NoiseComputeTask(int batchSize, int height, List<NoiseRequest> batch, int[] permutation) {
        this.batchSize = batchSize;
        this.height = height;
        this.batchRequests = batch;
        this.permutation = permutation;
    }

    @Override
    public void execute() {
        if (!GLShaderManager.isSupported()) {
            failBatch(batchRequests, "Compute Shaders not supported");
            return;
        }

        if (noiseProgram == -1) {
            String source = GLShaderManager.loadShaderSource("/assets/gpuloader/shaders/noise.comp", true);
            if (source != null) {
                noiseProgram = GLShaderManager.createComputeProgram("noise", source);
            }
            if (noiseProgram == -1) {
                failBatch(batchRequests, "Failed to compile noise compute shader");
                return;
            }
            
            // Cache uniform locations once
            loc_request_x = GL20.glGetUniformLocation(noiseProgram, "request_x");
            loc_request_z = GL20.glGetUniformLocation(noiseProgram, "request_z");
            loc_width = GL20.glGetUniformLocation(noiseProgram, "width");
            loc_height = GL20.glGetUniformLocation(noiseProgram, "height");
            loc_depth = GL20.glGetUniformLocation(noiseProgram, "depth");
            loc_batch_size = GL20.glGetUniformLocation(noiseProgram, "batch_size");
            loc_enable_biome = GL20.glGetUniformLocation(noiseProgram, "enable_biome");
        }

        // 定期的に古いバッチの完了をチェック
        processReadyBatches();

        int pointsPerChunk = width * height * depth;
        int requiredBytes = (pointsPerChunk * channels) * batchSize * Float.BYTES;
        PBOReadbackBuffer pbo = getAvailablePBO(requiredBytes);

        if (pbo == null) {
            executeSync(requiredBytes);
            return;
        }

        GL20.glUseProgram(noiseProgram);

        int[] reqX = new int[32];
        int[] reqZ = new int[32];
        for (int i = 0; i < batchSize; i++) {
            reqX[i] = batchRequests.get(i).chunkX * 16;
            reqZ[i] = batchRequests.get(i).chunkZ * 16;
        }
        GL20.glUniform1iv(loc_request_x, reqX);
        GL20.glUniform1iv(loc_request_z, reqZ);
        GL20.glUniform1i(loc_width, width);
        GL20.glUniform1i(loc_height, height);
        GL20.glUniform1i(loc_depth, depth);
        GL20.glUniform1i(loc_batch_size, batchSize);

        // Biome determination
        boolean biomeEnabled = BiomeGPUBuffer.isInitialized();
        GL20.glUniform1i(loc_enable_biome, biomeEnabled ? 1 : 0);

        bindPermBuffer(permutation);
        pbo.bindWriteBuffer(1);

        int totalPoints2D = width * depth * batchSize; // Dispatch 2D grid, loop over height in shader
        int localSize = GLShaderManager.getOptimalLocalSize();
        int groups = (totalPoints2D + localSize - 1) / localSize;
        GL43.glDispatchCompute(groups, 1, 1);

        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // Sync readback has been removed due to severe render thread stuttering.
        // Biome offloading architecture will need a redesign if reintroduced.

        pbo.insertFence();
        pbo.swap();

        PENDING_QUEUE.add(new PendingBatch(batchRequests, pbo, height, System.currentTimeMillis()));
    }

    public static void processReadyBatches() {
        int queueSize = PENDING_QUEUE.size();
        for (int i = 0; i < queueSize; i++) {
            PendingBatch pending = PENDING_QUEUE.peek();
            if (pending == null) break;

            ByteBuffer mapped = pending.pbo.tryRead(0L);
            if (mapped != null) {
                PENDING_QUEUE.poll();

                long latency = System.currentTimeMillis() - pending.submitTime;
                terrainBatchCount++;
                terrainTotalLatency += latency;
                terrainTotalChunks += pending.requests.size();
                
                terrain10mBatchCount++;
                terrain10mTotalLatency += latency;
                terrain10mTotalChunks += pending.requests.size();

                long now = System.currentTimeMillis();
                
                // 32-chunk periodic log
                if (terrainTotalChunks >= 32) {
                    if (com.gpuloader.Config.debugTerrainNoise) {
                        LOGGER.info("[GPU Terrain] Processed {} chunks on GPU. Avg Latency {}ms, Avg Batch Size {}",
                            terrainTotalChunks, terrainTotalLatency / terrainBatchCount, terrainTotalChunks / terrainBatchCount);
                    }
                    terrainBatchCount = 0;
                    terrainTotalLatency = 0;
                    terrainTotalChunks = 0;
                }

                FloatBuffer floats = mapped.asFloatBuffer();
                int floatsPerChunk = 5 * pending.height * 5 * 7;

                for (NoiseRequest req : pending.requests) {
                    // If the future was cancelled by cache cleanup, skip and don't leak a buffer
                    if (req.result.isCancelled()) {
                        // Advance the read position past this chunk's data
                        floats.position(floats.position() + floatsPerChunk);
                        continue;
                    }
                    FloatBuffer data = com.gpuloader.core.BufferPool.acquireNoiseBuffer();
                    if (data.capacity() >= floatsPerChunk) {
                        data.clear();
                        int oldLimit = floats.limit();
                        floats.limit(floats.position() + floatsPerChunk);
                        data.put(floats);
                        floats.limit(oldLimit);
                        data.flip();
                        // complete() returns false if someone cancelled/completed the future first
                        if (!req.result.complete(data)) {
                            com.gpuloader.core.BufferPool.releaseNoiseBuffer(data);
                        }
                    } else {
                        com.gpuloader.core.BufferPool.releaseNoiseBuffer(data);
                        req.result.completeExceptionally(new RuntimeException("Buffer size mismatch"));
                    }
                }
                pending.pbo.finishRead();
            } else {
                break;
            }
        }

        // 10-minute periodic log (outside the loop so it evaluates even when idle)
        long now = System.currentTimeMillis();
        if (terrainLastLogTime == 0) terrainLastLogTime = now;
        if (now - terrainLastLogTime >= 600000) {
            if (terrain10mBatchCount > 0) {
                LOGGER.info("[GPU Terrain] Stats (last 10m): Processed {} chunks on GPU. Avg Latency {}ms, Avg Batch Size {}",
                    terrain10mTotalChunks, terrain10mTotalLatency / terrain10mBatchCount, terrain10mTotalChunks / terrain10mBatchCount);
            } else {
                // Log that we're alive but idle
                LOGGER.info("[GPU Terrain] Stats (last 10m): 0 new chunks generated.");
            }
            terrainLastLogTime = now;
            terrain10mBatchCount = 0;
            terrain10mTotalLatency = 0;
            terrain10mTotalChunks = 0;
        }
    }

    private void executeSync(int requiredBytes) {
        int pointsPerChunk = width * height * depth;
        int tempBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, tempBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) requiredBytes, GL15.GL_STREAM_READ);
        GL32.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, tempBuffer);

        int totalPoints2D = width * depth * batchSize;
        int localSize = GLShaderManager.getOptimalLocalSize();
        int groups = (totalPoints2D + localSize - 1) / localSize;
        GL43.glDispatchCompute(groups, 1, 1);

        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        int totalFloats = (pointsPerChunk * channels) * batchSize;
        FloatBuffer res = BufferUtils.createFloatBuffer(totalFloats);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, res);

        int floatsPerChunk = pointsPerChunk * channels;
        for (NoiseRequest req : batchRequests) {
            FloatBuffer data = com.gpuloader.core.BufferPool.acquireNoiseBuffer();
            if (data.capacity() >= floatsPerChunk) {
                data.clear();
                int oldLimit = res.limit();
                res.limit(res.position() + floatsPerChunk);
                data.put(res);
                res.limit(oldLimit);
                data.flip();
                req.result.complete(data);
            } else {
                com.gpuloader.core.BufferPool.releaseNoiseBuffer(data);
                req.result.completeExceptionally(new RuntimeException("Buffer size mismatch"));
            }
        }

        GL15.glDeleteBuffers(tempBuffer);
    }

    private synchronized static PBOReadbackBuffer getAvailablePBO(int requiredBytes) {
        Set<PBOReadbackBuffer> inUse = new HashSet<>();
        for (PendingBatch b : PENDING_QUEUE)
            inUse.add(b.pbo);

        for (PBOReadbackBuffer pbo : PBO_POOL) {
            if (!inUse.contains(pbo) && pbo.getCapacity() >= requiredBytes)
                return pbo;
        }

        if (PBO_POOL.size() < POOL_SIZE) {
            PBOReadbackBuffer newPbo = new PBOReadbackBuffer(requiredBytes);
            newPbo.init();
            PBO_POOL.add(newPbo);
            return newPbo;
        }
        return null;
    }

    private static void bindPermBuffer(int[] currentPerm) {
        if (permBuffer == -1) {
            permBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, permBuffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 512L * Integer.BYTES, GL15.GL_DYNAMIC_DRAW);
        }

        if (currentPerm != null) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, permBuffer);
            IntBuffer p = BufferUtils.createIntBuffer(512);
            p.put(currentPerm).flip();
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, p);
        }
        GL32.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, permBuffer);
    }

    private void failBatch(List<NoiseRequest> requests, String reason) {
        RuntimeException ex = new RuntimeException(reason);
        for (NoiseRequest req : requests)
            req.result.completeExceptionally(ex);
    }

    public static void cleanup() {
        for (PBOReadbackBuffer p : PBO_POOL)
            p.cleanup();
        PBO_POOL.clear();
        PENDING_QUEUE.clear();
        if (permBuffer != -1) {
            GL15.glDeleteBuffers(permBuffer);
            permBuffer = -1;
        }
        noiseProgram = -1;
    }
}
