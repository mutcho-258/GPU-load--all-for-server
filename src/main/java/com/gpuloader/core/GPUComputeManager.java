package com.gpuloader.core;

import com.gpuloader.api.IGPUComputeAPI;
import com.gpuloader.api.IGPUTask;
import com.gpuloader.api.ShaderCompileOptions;
import com.gpuloader.gl.GLShaderManager;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GPU計算タスクを管理するクラス。
 * OpenGLコマンドを安全に実行するため、全てのタスクはレンダースレッド上で処理されます。
 */
public class GPUComputeManager implements IGPUComputeAPI {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final GPUComputeManager INSTANCE = new GPUComputeManager();
    
    public static GPUComputeManager getInstance() {
        return INSTANCE;
    }

    /** レンダースレッドで実行待ちのタスクキュー */
    private static final Queue<IGPUTask<?>> RENDER_THREAD_QUEUE = new ConcurrentLinkedQueue<>();

    /**
     * レンダースレッドで実行するタスクをキューに追加する。
     */
    public static void submitRenderTask(GPUTask<?> task) {
        RENDER_THREAD_QUEUE.add(task);
    }

    @Override
    public <T> void submitTask(IGPUTask<T> task) {
        RENDER_THREAD_QUEUE.add(task);
    }

    @Override
    public boolean isSupported() {
        return GLShaderManager.isSupported();
    }

    @Override
    public int getOptimalWorkgroupSize() {
        return GLShaderManager.getOptimalLocalSize();
    }

    @Override
    public boolean registerShader(ResourceLocation id, String source) {
        // ResourceLocationを文字列に変換して内部マネージャーに登録
        return GLShaderManager.createComputeProgram(id.toString(), source) != -1;
    }

    @Override
    public boolean registerShader(ResourceLocation id, String source, ShaderCompileOptions options) {
        if (options == null) {
            return registerShader(id, source);
        }
        return GLShaderManager.createComputeProgram(
                id.toString(), source,
                options.getDefines(),
                options.shouldInjectLocalSize()
        ) != -1;
    }

    @Override
    public void dispatchShader(ResourceLocation id, int x, int y, int z) {
        useShader(id);
        GL43.glDispatchCompute(x, y, z);
    }

    @Override
    public void useShader(ResourceLocation id) {
        GLShaderManager.useProgram(id.toString());
    }

    @Override
    public int getShaderProgramId(ResourceLocation id) {
        return GLShaderManager.getProgram(id.toString());
    }

    @Override
    public String getGpuRenderer() {
        return GLShaderManager.getGpuRenderer();
    }

    @Override
    public String getGpuVendor() {
        return GLShaderManager.getGpuVendor();
    }

    @Override
    public boolean isIntegratedGPU() {
        return GLShaderManager.isIntegratedGPU();
    }

    @Override
    public com.gpuloader.api.IPersistentBuffer createPersistentBuffer(int inputCapacity, int outputCapacity) {
        return new com.gpuloader.gl.PersistentBufferImpl(inputCapacity, outputCapacity);
    }

    /**
     * キューに溜まったタスクをレンダースレッド上で順次実行する。
     * GPUMod の TickEvent から呼ぶこと。
     */
    public static void processRenderThreadTasks() {
        IGPUTask<?> task;
        while ((task = RENDER_THREAD_QUEUE.poll()) != null) {
            try {
                task.execute();
            } catch (Exception e) {
                LOGGER.error("Error executing Render Thread GPU task", e);
                task.getFuture().completeExceptionally(e);
            }
        }
    }
}

