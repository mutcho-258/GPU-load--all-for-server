package com.gpuloader.client;

import com.gpuloader.Config;
import com.gpuloader.GPUMod;
import com.gpuloader.gl.EntityMdiManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

/**
 * Forgeの描画イベントを購読し、MDI描画への差し替えを行うイベントハンドラー。
 */
@Mod.EventBusSubscriber(modid = GPUMod.MODID, value = Dist.CLIENT)
public class EntityMdiEventHandler {

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!GPUMod.initialized) return;
        if (!Config.entityMDI) return;

        // レンダースレッド上でMDIマネージャーを遅延初期化
        if (!EntityMdiManager.isInitialized()) {
            EntityMdiManager.init();
        }

        if (EntityMdiManager.isInitialized()) {
            net.minecraft.resources.ResourceLocation texture = 
                ((net.minecraft.client.renderer.entity.EntityRenderer) event.getRenderer()).getTextureLocation(event.getEntity());
            boolean queued = EntityMdiManager.queueEntity(
                    event.getEntity(),
                    event.getPoseStack(),
                    event.getPackedLight(),
                    texture,
                    event.getMultiBufferSource(),
                    event.getPartialTick()
            );
            if (queued) {
                event.setCanceled(true); // バニラの通常レンダリングをキャンセル
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!GPUMod.initialized) return;
        if (!Config.entityMDI) return;

        if (EntityMdiManager.isInitialized()) {
            EntityMdiManager.cachedProjMatrix = event.getProjectionMatrix();
        }

        // エンティティ描画ステージの終了時に一括描画 (セーフティ・フォールバック)
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            if (EntityMdiManager.isInitialized()) {
                EntityMdiManager.flush(event.getPoseStack(), event.getProjectionMatrix());
            }
        }
    }
}
