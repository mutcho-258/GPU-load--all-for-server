package com.gpuloader.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GPU計算タスクを管理するクラス。
 * OpenGLコマンドを安全に実行するため、全てのタスクはレンダースレッド上で処理されます。
 */
public class GPUComputeManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /** レンダースレッドで実行待ちのタスクキュー */
    private static final Queue<GPUTask<?>> RENDER_THREAD_QUEUE = new ConcurrentLinkedQueue<>();

    /**
     * レンダースレッドで実行するタスクをキューに追加する。
     */
    public static void submitRenderTask(GPUTask<?> task) {
        RENDER_THREAD_QUEUE.add(task);
    }

    /**
     * キューに溜まったタスクをレンダースレッド上で順次実行する。
     * GPUMod の TickEvent から呼ぶこと。
     */
    public static void processRenderThreadTasks() {
        GPUTask<?> task;
        while ((task = RENDER_THREAD_QUEUE.poll()) != null) {
            try {
                task.execute();
            } catch (Exception e) {
                LOGGER.error("Error executing Render Thread GPU task", e);
                task.result.completeExceptionally(e);
            }
        }
    }
}
