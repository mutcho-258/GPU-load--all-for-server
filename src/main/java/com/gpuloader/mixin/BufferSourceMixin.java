package com.gpuloader.mixin;

import com.gpuloader.gl.EntityMdiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.renderer.MultiBufferSource.BufferSource.class)
public class BufferSourceMixin {
    @Inject(method = "endBatch()V", at = @At("HEAD"))
    private void onEndBatchHead(CallbackInfo ci) {
        // CPUのバッファ（防具・衣装・半透明ゲルなど）がフラッシュして描画される直前に、
        // GPU側のMDIベースモデルを描画し、デプスバッファを構築します。
        EntityMdiManager.flushDirect();
    }
}
