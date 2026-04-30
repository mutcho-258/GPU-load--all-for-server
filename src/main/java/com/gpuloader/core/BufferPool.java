package com.gpuloader.core;

import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * GC圧力を軽減するための配列プールクラス。
 * 7チャンネル（地形密度 + 6気候パラメータ）を収容可能なサイズに拡張。
 * Noise系: Native (Off-Heap) FloatBuffer を使用してGCを完全にバイパス。
 * Mesh/Opacity系: 小サイズのためJava int[] を使用（GC影響は無視可能）。
 */
public class BufferPool {

    /** ノイズ計算結果用 (5x33x5 = 825 floats * 7 channels = 5775 floats) */
    private static final int NOISE_SIZE = 5775;
    private static final BlockingQueue<FloatBuffer> NOISE_POOL = new ArrayBlockingQueue<>(256);

    /** メッシュカリング結果用 (16x16x16 = 4096 ints) */
    private static final int MESH_SIZE = 4096;
    private static final BlockingQueue<int[]> MESH_POOL = new ArrayBlockingQueue<>(128);

    /** 不透明度フラグ用 (4096 / 32 = 128 ints) */
    private static final int OPACITY_SIZE = 128;
    private static final BlockingQueue<int[]> OPACITY_POOL = new ArrayBlockingQueue<>(128);

    // ---- Float Buffers (Noise) - Off-Heap ----

    public static FloatBuffer acquireNoiseBuffer() {
        FloatBuffer buf = NOISE_POOL.poll();
        return buf != null ? buf : MemoryUtil.memAllocFloat(NOISE_SIZE);
    }

    public static void releaseNoiseBuffer(FloatBuffer buf) {
        if (buf != null && buf.capacity() == NOISE_SIZE) {
            buf.clear();
            if (!NOISE_POOL.offer(buf)) {
                MemoryUtil.memFree(buf);
            }
        }
    }

    // ---- Int Buffers (Mesh) - On-Heap ----

    public static int[] acquireMeshBuffer() {
        int[] buf = MESH_POOL.poll();
        return buf != null ? buf : new int[MESH_SIZE];
    }

    public static void releaseMeshBuffer(int[] buf) {
        if (buf != null && buf.length == MESH_SIZE) {
            MESH_POOL.offer(buf);
        }
    }

    // ---- Int Buffers (Opacity) - On-Heap ----

    public static int[] acquireOpacityBuffer() {
        int[] buf = OPACITY_POOL.poll();
        return buf != null ? buf : new int[OPACITY_SIZE];
    }

    public static void releaseOpacityBuffer(int[] buf) {
        if (buf != null && buf.length == OPACITY_SIZE) {
            OPACITY_POOL.offer(buf);
        }
    }

    public static void cleanup() {
        FloatBuffer fBuf;
        while ((fBuf = NOISE_POOL.poll()) != null) MemoryUtil.memFree(fBuf);

        MESH_POOL.clear();
        OPACITY_POOL.clear();
    }
}
