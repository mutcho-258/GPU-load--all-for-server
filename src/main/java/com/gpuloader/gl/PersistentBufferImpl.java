package com.gpuloader.gl;

import com.gpuloader.api.IPersistentBuffer;
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
 * IPersistentBuffer の標準実装。
 * CPU-GPU間の非同期データ転送をダブルバッファリングによって実現する汎用クラスです。
 */
public class PersistentBufferImpl implements IPersistentBuffer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int inputCapacity;
    private final int outputCapacity;

    // ---- Upload (CPU→GPU) ダブルバッファ ----
    private final int[] inputBuffers = new int[2];
    private final ByteBuffer[] mappedInput = new ByteBuffer[2];

    // ---- Readback (GPU→CPU) ダブルバッファ ----
    private final int[] outputBuffers = new int[2];
    private final ByteBuffer[] mappedOutput = new ByteBuffer[2];

    private final long[] outputFences = {0L, 0L};
    private int writeIndex = 0;
    private boolean initialized = false;

    public PersistentBufferImpl(int inputCapacity, int outputCapacity) {
        this.inputCapacity = inputCapacity;
        this.outputCapacity = outputCapacity;
    }

    @Override
    public void init() {
        if (initialized) return;

        // Upload バッファ: CPU書き込み専用
        int writeStorageFlags = GL44.GL_MAP_WRITE_BIT |
                                GL44.GL_MAP_PERSISTENT_BIT |
                                GL44.GL_MAP_COHERENT_BIT;

        for (int i = 0; i < 2; i++) {
            inputBuffers[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, inputBuffers[i]);
            GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, inputCapacity, writeStorageFlags);
            mappedInput[i] = GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, inputCapacity, writeStorageFlags);
            if (mappedInput[i] == null) {
                LOGGER.error("[GPU Loader] Failed to persistent map input buffer {}! Capacity: {}", i, inputCapacity);
            }
        }

        // Readback バッファ: GPU書き込み → CPU読み取り
        int readStorageFlags = GL44.GL_MAP_PERSISTENT_BIT |
                               GL44.GL_MAP_COHERENT_BIT |
                               GL30.GL_MAP_READ_BIT |
                               GL44.GL_DYNAMIC_STORAGE_BIT;

        int readMapFlags = GL44.GL_MAP_PERSISTENT_BIT |
                           GL44.GL_MAP_COHERENT_BIT |
                           GL30.GL_MAP_READ_BIT;

        for (int i = 0; i < 2; i++) {
            outputBuffers[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, outputBuffers[i]);
            GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, outputCapacity, readStorageFlags);
            mappedOutput[i] = GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, outputCapacity, readMapFlags);
            if (mappedOutput[i] == null) {
                LOGGER.error("[GPU Loader] Failed to persistent map output buffer {}! Capacity: {}", i, outputCapacity);
            }
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        initialized = true;
    }

    @Override
    public ByteBuffer getWriteInputBuffer() {
        if (!initialized) return null;
        ByteBuffer buf = mappedInput[writeIndex];
        if (buf != null) buf.clear();
        return buf;
    }

    @Override
    public void bindInputBuffer(int bindingPoint) {
        if (!initialized) return;
        GL32.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingPoint, inputBuffers[writeIndex]);
    }

    @Override
    public void bindOutputBuffer(int bindingPoint) {
        if (!initialized) return;
        GL32.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingPoint, outputBuffers[writeIndex]);
    }

    @Override
    public void insertOutputFence() {
        if (!initialized) return;
        if (outputFences[writeIndex] != 0L) {
            GL32.glDeleteSync(outputFences[writeIndex]);
        }
        outputFences[writeIndex] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
    }

    @Override
    public void swap() {
        writeIndex ^= 1;
    }

    @Override
    public ByteBuffer tryReadOutput(long timeoutNanos) {
        if (!initialized) return null;

        int readIndex = writeIndex ^ 1;
        long fence = outputFences[readIndex];
        if (fence == 0L) return null;

        int status = GL32.glClientWaitSync(fence, 0, timeoutNanos);
        if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
            ByteBuffer buf = mappedOutput[readIndex];
            if (buf != null) {
                buf.clear();
            }
            return buf;
        }

        return null;
    }

    @Override
    public void finishRead() {
        if (!initialized) return;
        int readIndex = writeIndex ^ 1;
        if (outputFences[readIndex] != 0L) {
            GL32.glDeleteSync(outputFences[readIndex]);
            outputFences[readIndex] = 0L;
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void cleanup() {
        if (!initialized) return;

        for (int i = 0; i < 2; i++) {
            if (outputFences[i] != 0L) {
                GL32.glDeleteSync(outputFences[i]);
                outputFences[i] = 0L;
            }

            if (inputBuffers[i] != 0) {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, inputBuffers[i]);
                GL15.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
                GL15.glDeleteBuffers(inputBuffers[i]);
                inputBuffers[i] = 0;
            }
            mappedInput[i] = null;

            if (outputBuffers[i] != 0) {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, outputBuffers[i]);
                GL15.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
                GL15.glDeleteBuffers(outputBuffers[i]);
                outputBuffers[i] = 0;
            }
            mappedOutput[i] = null;
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        initialized = false;
    }
}
