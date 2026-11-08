package com.gpuloader.mixin;

import com.gpuloader.Config;
import com.gpuloader.core.LightBatchManager;
import com.gpuloader.gl.GLShaderManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {

    @Shadow
    @Final
    private LightEngine<?, ?> blockEngine;

    @Inject(method = "propagateLightSources", at = @At("HEAD"), cancellable = true)
    private void onPropagateLightSources(ChunkPos pos, CallbackInfo ci) {
        if (Config.gpuLighting && GLShaderManager.isSupported() && this.blockEngine != null) {
            LightChunkGetter chunkSource = ((LightEngineAccessor) this.blockEngine).getChunkSource();
            if (chunkSource != null) {
                LightChunk lightChunk = chunkSource.getChunkForLighting(pos.x, pos.z);
                if (lightChunk instanceof ChunkAccess chunk) {
                    boolean handled = LightBatchManager.processChunkSynchronous(
                            (LevelLightEngine) (Object) this,
                            chunk
                    );
                    if (handled) {
                        ci.cancel(); // バニラの重い CPU BFS をキャンセル
                    }
                }
            }
        }
    }
}
