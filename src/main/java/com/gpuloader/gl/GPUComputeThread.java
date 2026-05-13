package com.gpuloader.gl;

import com.gpuloader.Config;
import com.gpuloader.core.GPUTask;
import com.mojang.blaze3d.platform.Window;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * レンダースレッドとは独立してGPU計算を行うための専用スレッド。
 * 共有コンテキストを使用することで、フレーム更新を待たずに即座にCompute Shaderを起動できる。
 */
public class GPUComputeThread extends Thread {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static GPUComputeThread INSTANCE;

    private final long sharedContext;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Queue<GPUTask<?>> taskQueue = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private GLCapabilities capabilities;

    private GPUComputeThread(long mainWindowHandle) {
        super("GPU-Compute-Worker");
        
        // メインウィンドウと共有する、非表示のダミーウィンドウ（コンテキスト用）を作成
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        if (mainWindowHandle == MemoryUtil.NULL) {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        }
        this.sharedContext = GLFW.glfwCreateWindow(1, 1, "GPU-Loader-Compute-Context", MemoryUtil.NULL, mainWindowHandle);
        
        if (this.sharedContext == MemoryUtil.NULL) {
            throw new RuntimeException("Failed to create shared OpenGL context for compute!");
        }
    }

    public static synchronized void init() {
        if (INSTANCE != null || !Config.useSharedContext) return;

        try {
            long handle;
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                handle = ClientWindowHelper.getMainWindowHandle();
            } else {
                if (!GLFW.glfwInit()) {
                    throw new RuntimeException("Failed to initialize GLFW on dedicated server");
                }
                handle = MemoryUtil.NULL;
            }
            INSTANCE = new GPUComputeThread(handle);
            INSTANCE.start();
            LOGGER.info("[GPU Loader] Background Compute Thread started. (Shared Context: {})", handle != MemoryUtil.NULL);
        } catch (Exception e) {
            LOGGER.error("Failed to start GPU Compute Thread", e);
        }
    }

    public static void submitTask(GPUTask<?> task) {
        if (INSTANCE != null && INSTANCE.running.get()) {
            INSTANCE.taskQueue.add(task);
            synchronized (INSTANCE.lock) {
                INSTANCE.lock.notifyAll();
            }
        } else {
            // フォールバック: レンダースレッドで実行（旧方式）
            com.gpuloader.core.GPUComputeManager.submitRenderTask(task);
        }
    }

    @Override
    public void run() {
        // コンテキストをこのスレッドに紐付け
        GLFW.glfwMakeContextCurrent(sharedContext);
        this.capabilities = GL.createCapabilities();
        
        LOGGER.info("[GPU Loader] Background Compute Context is now active.");

        // Dedicated server has no main context, so we compile shaders here
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isDedicatedServer()) {
            try {
                com.gpuloader.gl.GLShaderManager.init();
                LOGGER.info("[GPU Loader] Shaders compiled successfully on background context.");
            } catch (Exception e) {
                LOGGER.error("Failed to compile shaders on dedicated server", e);
                running.set(false);
            }
        }

        while (running.get()) {
            GPUTask<?> task = taskQueue.poll();
            if (task != null) {
                try {
                    task.execute();
                } catch (Exception e) {
                    LOGGER.error("Error in background GPU task", e);
                    task.result.completeExceptionally(e);
                }
            }
            
            // 完了したバッチの読み戻しを常にチェック（タスク実行後も、待機前も）
            com.gpuloader.gl.NoiseComputeTask.processReadyBatches();
            com.gpuloader.gl.MobAiTask.processReadyBatches();

            if (task == null) {
                synchronized (lock) {
                    try {
                        // 待機時間を100msから2msに大幅短縮し、レスポンス性を向上
                        lock.wait(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        GLFW.glfwDestroyWindow(sharedContext);
        LOGGER.info("[GPU Loader] Background Compute Thread stopped.");
    }

    public static void shutdown() {
        if (INSTANCE != null) {
            INSTANCE.running.set(false);
            synchronized (INSTANCE.lock) {
                INSTANCE.lock.notifyAll();
            }
            try {
                INSTANCE.join(1000);
            } catch (InterruptedException e) {
                LOGGER.error("Failed to join GPU Compute Thread", e);
            }
            INSTANCE = null;
        }
    }
}
