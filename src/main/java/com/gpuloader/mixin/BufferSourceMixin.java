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
        // MDIモデルは RenderLevelStageEvent.Stage.AFTER_ENTITIES で一括描画されるため、
        // バニラのBufferSource.endBatch()への割り込みは不要（チェスト・鐘等の固定バッファ描画を保護するため無効化）
    }
    // endBatch(RenderType) へのフックは意図的に外しています。
    // layer.render() 内でのgetBuffer()呼び出しが引き起こす中間endBatch(RenderType)で
    // flushDirect()が複数回呼ばれると、BATCHES.clear()後に服レイヤーが描画されるため
    // 服が見えなくなります。
}
