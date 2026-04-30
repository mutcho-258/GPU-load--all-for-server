package com.gpuloader.gl;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * GPU→CPU非同期データ転送を管理するPBOダブルバッファクラス。
 *
 * 【ZERO-STALL版】
 *  - Persistent Mapping (GL_MAP_PERSISTENT_BIT) を使用
 *  - フェンスシンクによる非同期データ回収
 */
public class PBOReadbackBuffer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** GPUが出力を書き込むSSBO */
    private final int[] buffers = new int[2];

    /** 各バッファに対応するフェンスシンクオブジェクト */
    private final long[] fences = {0L, 0L};

    /** 永続マッピングされたバッファのポインタ */
    private final ByteBuffer[] mappedBuffers = new ByteBuffer[2];

    /** 現在書き込み中のバッファインデックス (0 or 1) */
    private int writeIndex = 0;

    /** バッファの確保サイズ（バイト） */
    private final int capacity;

    /** 初期化済みフラグ */
    private boolean initialized = false;

    public PBOReadbackBuffer(int capacityBytes) {
        this.capacity = capacityBytes;
    }

    /**
     * バッファを初期化する。
     */
    public void init() {
        if (initialized) return;

        // 【修正】glBufferStorage と glMapBufferRange で使用可能なフラグを分離
        // StorageFlags: 作成時の権限と性質を指定
        int storageFlags = GL44.GL_MAP_PERSISTENT_BIT | 
                           GL44.GL_MAP_COHERENT_BIT | 
                           GL30.GL_MAP_READ_BIT |
                           GL44.GL_DYNAMIC_STORAGE_BIT;

        // MapFlags: マッピング自体に必要な権限のみを指定 (GL_DYNAMIC_STORAGE_BIT は含めない)
        int mapFlags = GL44.GL_MAP_PERSISTENT_BIT | 
                       GL44.GL_MAP_COHERENT_BIT | 
                       GL30.GL_MAP_READ_BIT;

        for (int i = 0; i < 2; i++) {
            buffers[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffers[i]);
            
            // glBufferStorage には storageFlags を渡す
            GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, capacity, storageFlags);
            
            // glMapBufferRange には mapFlags のみを渡す (GL_INVALID_ENUM 回避)
            mappedBuffers[i] = GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, capacity, mapFlags);
            
            if (mappedBuffers[i] == null) {
                LOGGER.error("[GPU Loader] Failed to persistent map buffer {}!", i);
            }
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        initialized = true;
        LOGGER.info("[GPU Loader] PBOReadbackBuffer initialized with Persistent Mapping.");
    }

    public void bindWriteBuffer(int bindingPoint) {
        if (!initialized) return;
        GL32.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingPoint, buffers[writeIndex]);
    }

    public void insertFence() {
        if (!initialized) return;

        if (fences[writeIndex] != 0L) {
            GL32.glDeleteSync(fences[writeIndex]);
        }
        fences[writeIndex] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
    }

    public ByteBuffer tryRead(long timeoutNanos) {
        if (!initialized) return null;

        int readIndex = writeIndex ^ 1;
        long fence = fences[readIndex];

        if (fence == 0L) return null;

        int status = GL32.glClientWaitSync(fence, 0, timeoutNanos);

        if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
            ByteBuffer buf = mappedBuffers[readIndex];
            if (buf != null) {
                buf.clear();
            }
            return buf;
        }

        return null;
    }

    public void finishRead() {
        if (!initialized) return;
        int readIndex = writeIndex ^ 1;
        
        if (fences[readIndex] != 0L) {
            GL32.glDeleteSync(fences[readIndex]);
            fences[readIndex] = 0L;
        }
    }

    public void swap() {
        writeIndex ^= 1;
    }

    public int getWriteBufferId() {
        return buffers[writeIndex];
    }

    public void cleanup() {
        for (int i = 0; i < 2; i++) {
            if (fences[i] != 0L) {
                GL32.glDeleteSync(fences[i]);
                fences[i] = 0L;
            }
            if (buffers[i] != 0) {
                GL15.glDeleteBuffers(buffers[i]);
            }
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getCapacity() {
        return capacity;
    }
}
