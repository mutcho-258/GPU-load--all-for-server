package com.gpuloader.core;

import com.gpuloader.api.IGPUTask;
import java.util.concurrent.CompletableFuture;

public abstract class GPUTask<T> implements IGPUTask<T> {
    public final CompletableFuture<T> result = new CompletableFuture<>();

    @Override
    public abstract void execute();

    @Override
    public CompletableFuture<T> getFuture() {
        return result;
    }
}
