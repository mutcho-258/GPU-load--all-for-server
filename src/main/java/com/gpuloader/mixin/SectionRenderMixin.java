package com.gpuloader.mixin;

import com.gpuloader.Config;
import com.gpuloader.core.MeshCullManager;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkRenderDispatcher.RenderChunk.class)
public abstract class SectionRenderMixin {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    @Shadow(aliases = "m_112814_")
    public abstract BlockPos getOrigin();

    /**
     * Chunk の再構築（compile）が始まる直前に、SSBO不透明度データを収集してGPUタスクを発行する。
     */
    @Inject(method = {"compile", "m_173711_", "m_224535_"}, at = @At("HEAD"), require = 0)
    private void onCompileHead(CallbackInfo ci) {
        if (!com.gpuloader.Config.chunkMesh || !com.gpuloader.GPUMod.initialized) return;

        BlockPos origin = getOrigin();
        int chunkX = origin.getX() >> 4;
        int chunkY = origin.getY() >> 4;
        int chunkZ = origin.getZ() >> 4;

        // クライアント側のワールドからチャンクを取得
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;

        net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunk(chunkX, chunkZ);
        if (chunk == null) return;

        // 対象のセクションを取得（Y座標からインデックス計算）
        int sectionIndex = chunk.getSectionIndex(origin.getY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return;

        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null || section.hasOnlyAir()) return;

        // GPUにカリング計算をリクエスト
        MeshCullManager.requestCull(chunkX, chunkY, chunkZ, section);
    }
}
