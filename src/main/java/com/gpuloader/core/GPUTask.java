package com.gpuloader.core;

import java.util.concurrent.CompletableFuture;

public abstract class GPUTask<T> {
    public final CompletableFuture<T> result = new CompletableFuture<>();

    public abstract void execute();
}
