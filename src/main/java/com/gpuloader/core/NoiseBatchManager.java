package com.gpuloader.core;

import com.gpuloader.Config;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * チャンク生成のノイズ計算をバッチングしてGPUへ投げるマネージャー。
 * 需要（実際の要求）を0.5ms待機して集め、足りない分を予測（BFS）で埋める
 * 「ハイブリッド・ダイナミックバッチング」を実装。
 */
public class NoiseBatchManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // 需要（CPUスレッドが実際に欲しがっているチャンク）のキュー
    private static final ConcurrentLinkedQueue<NoiseRequest> DEMAND_QUEUE = new ConcurrentLinkedQueue<>();
    
    private static int currentHeight = 33;
    private static int[] currentPermutation = null;
    private static final ConcurrentHashMap<ChunkPos, CachedChunk> CACHE = new ConcurrentHashMap<>();

    // 0.5ms待機用のスケジューラ
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Noise-Batch-Scheduler");
        t.setDaemon(true);
        return t;
    });
    
    private static final AtomicBoolean GATHERING = new AtomicBoolean(false);
    private static ScheduledFuture<?> pendingFlush = null;

    public static void updatePermutation(long seed) {
        int[] p_base = new int[256];
        for (int i = 0; i < 256; i++) p_base[i] = i;
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int temp = p_base[i];
            p_base[i] = p_base[j];
            p_base[j] = temp;
        }
        currentPermutation = new int[512];
        for (int i = 0; i < 256; i++)
            currentPermutation[i] = currentPermutation[i + 256] = p_base[i];
    }

    public record ChunkPos(int x, int z) {}

    public static class CachedChunk {
        public final CompletableFuture<java.nio.FloatBuffer> future;
        public final int height;
        public long lastAccessTime;
        public volatile boolean released;

        public CachedChunk(CompletableFuture<java.nio.FloatBuffer> future, int height) {
            this.future = future;
            this.height = height;
            this.lastAccessTime = System.currentTimeMillis();
            this.released = false;
        }
    }

    public static class NoiseRequest {
        public final int chunkX, chunkZ, height;
        public final CompletableFuture<java.nio.FloatBuffer> result;

        public NoiseRequest(int chunkX, int chunkZ, int height, CompletableFuture<java.nio.FloatBuffer> result) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.height = height;
            this.result = result;
        }
    }

    public static void setHeight(int height) {
        currentHeight = height;
    }

    private static int requestCount = 0;

    public static CompletableFuture<java.nio.FloatBuffer> requestChunk(int chunkX, int chunkZ) {
        if (++requestCount % 64 == 0) {
            cleanupCache();
        }

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        boolean[] isNew = { false };

        CachedChunk cached = CACHE.computeIfAbsent(pos, p -> {
            isNew[0] = true;
            return new CachedChunk(new CompletableFuture<>(), currentHeight);
        });

        // lastAccessTime の更新も毎秒数千回走るため、書き込み時のみにするか、
        // あるいは更新頻度を下げる（ここでは一旦削除して挿入時のみに依存させる）
        // cached.lastAccessTime = System.currentTimeMillis();

        if (isNew[0]) {
            DEMAND_QUEUE.add(new NoiseRequest(chunkX, chunkZ, currentHeight, cached.future));
            
            // 需要がバッチサイズに達したら即実行
            if (DEMAND_QUEUE.size() >= Config.terrainNoiseBatch) {
                flush();
            } else {
                // 最初の要求なら0.5msの待機窓を開始
                if (GATHERING.compareAndSet(false, true)) {
                    long delayMicros = (long)(Config.computeBatchGatherMs * 1000.0);
                    synchronized (NoiseBatchManager.class) {
                        if (pendingFlush != null) pendingFlush.cancel(false);
                        pendingFlush = SCHEDULER.schedule(NoiseBatchManager::flush, delayMicros, TimeUnit.MICROSECONDS);
                    }
                }
            }
        }

        return cached.future;
    }

    public static synchronized void flush() {
        GATHERING.set(false);
        if (pendingFlush != null) {
            pendingFlush.cancel(false);
            pendingFlush = null;
        }
        
        if (DEMAND_QUEUE.isEmpty()) return;
        executeNextBatch();
    }

    private static void executeNextBatch() {
        List<NoiseRequest> batch = new ArrayList<>();
        
        // 1. 需要(DEMAND)を優先して取り出す
        while (!DEMAND_QUEUE.isEmpty() && batch.size() < Config.terrainNoiseBatch) {
            batch.add(DEMAND_QUEUE.poll());
        }

        // 2. 予測(BFS)によるパディングは、無駄な計算（GPU負荷）を増やし
        // かえって本来の要求のレスポンスを下げる可能性があるため一旦停止。
        // 自然な需要（複数スレッドからの要求）だけでバッチを組む。
        /*
        if (batch.size() < Config.terrainNoiseBatch && !batch.isEmpty()) {
            NoiseRequest lead = batch.get(0);
            padWithPredictions(batch, lead.chunkX, lead.chunkZ);
        }
        */

        if (batch.isEmpty()) return;

        // 3. 計算専用スレッド（共有コンテキスト）またはレンダースレッドに送信
        com.gpuloader.gl.NoiseComputeTask task = new com.gpuloader.gl.NoiseComputeTask(
                batch.size(), batch.get(0).height, batch, currentPermutation);
        
        if (Config.useSharedContext) {
            com.gpuloader.gl.GPUComputeThread.submitTask(task);
        } else {
            com.gpuloader.core.GPUComputeManager.submitRenderTask(task);
        }
    }

    /**
     * BFSを用いて隣接チャンクを予測し、バッチの空きスロットを埋める。
     */
    private static void padWithPredictions(List<NoiseRequest> batch, int startX, int startZ) {
        int maxBatch = Config.terrainNoiseBatch;
        Queue<ChunkPos> queue = new LinkedList<>();
        java.util.Set<ChunkPos> visited = new java.util.HashSet<>();

        // すでにバッチに入っている座標を既知とする
        for (NoiseRequest r : batch) visited.add(new ChunkPos(r.chunkX, r.chunkZ));
        
        ChunkPos start = new ChunkPos(startX, startZ);
        queue.add(start);

        int[][] directions = { { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 } };

        while (!queue.isEmpty() && batch.size() < maxBatch) {
            ChunkPos curr = queue.poll();
            for (int[] dir : directions) {
                if (batch.size() >= maxBatch) break;
                ChunkPos neighbor = new ChunkPos(curr.x + dir[0], curr.z + dir[1]);

                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);

                    boolean[] neighborNew = { false };
                    CachedChunk neighborCache = CACHE.computeIfAbsent(neighbor, p -> {
                        neighborNew[0] = true;
                        return new CachedChunk(new CompletableFuture<>(), currentHeight);
                    });

                    if (neighborNew[0]) {
                        batch.add(new NoiseRequest(neighbor.x, neighbor.z, currentHeight, neighborCache.future));
                    }
                }
            }
        }
    }

    private static void cleanupCache() {
        long now = System.currentTimeMillis();
        CACHE.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().lastAccessTime) > 10000;
            if (expired) releaseCachedBuffer(entry.getValue());
            return expired;
        });
    }

    private static synchronized void releaseCachedBuffer(CachedChunk cached) {
        if (cached.released) return;
        cached.released = true;
        CompletableFuture<java.nio.FloatBuffer> f = cached.future;
        if (f.isDone() && !f.isCompletedExceptionally() && !f.isCancelled()) {
            java.nio.FloatBuffer buf = f.getNow(null);
            if (buf != null) BufferPool.releaseNoiseBuffer(buf);
        }
        f.cancel(false);
    }

    public static void clearCache() {
        CACHE.values().forEach(NoiseBatchManager::releaseCachedBuffer);
        CACHE.clear();
        DEMAND_QUEUE.clear();
        LOGGER.info("[GPU Loader] Noise cache cleared.");
    }
}
