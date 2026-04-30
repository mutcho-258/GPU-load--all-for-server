package com.gpuloader.mixin;

import com.gpuloader.Config;
import com.gpuloader.core.MeshCullManager;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ブロックモデルの描画ループに介入し、GPU計算済みのカリング結果を適用するMixin。
 */
@Mixin(ModelBlockRenderer.class)
public abstract class BlockModelRendererMixin {

    /**
     * shouldRenderFace (または相当する面表示判定メソッド) にフックし、
     * GPUが「不要」と判断した面を強制的に非表示にする。
     *
     * 注: Net.minecraft.client.renderer.block.BlockModelRenderer のメソッド構成に依存。
     * 1.20.1 では主に tesselateBlock 内部や、専用の可視性チェックメソッドが呼ばれる。
     */
    @Inject(method = {"shouldRenderFace", "m_111066_", "m_224536_"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void onShouldRenderFace(BlockAndTintGetter level, BlockState state, BlockPos pos, Direction face, BlockPos neighborPos, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.chunkMesh || !com.gpuloader.GPUMod.initialized) return;

        // セクション内のローカル座標を算出
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;

        // キャッシュキーを生成
        long sectionKey = MeshCullManager.makeSectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);

        // キャッシュが存在する場合のみGPUカリングを適用
        if (MeshCullManager.isCached(sectionKey)) {
            // face.ordinal(): 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST
            // MeshCullTaskでの定義: 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
            // マッピング変換が必要
            int gpuFaceIdx = mapVanillaFaceToGpu(face);

            if (!MeshCullManager.isFaceVisible(sectionKey, localX, localY, localZ, gpuFaceIdx)) {
                cir.setReturnValue(false);
            }
        }
    }

    /**
     * Vanillaの Direction を MeshCullTask (mesh.comp) のインデックスに変換する。
     * mesh.comp: 0=+X(EAST), 1=-X(WEST), 2=+Y(UP), 3=-Y(DOWN), 4=+Z(SOUTH), 5=-Z(NORTH)
     */
    private int mapVanillaFaceToGpu(Direction face) {
        return switch (face) {
            case EAST -> 0;
            case WEST -> 1;
            case UP -> 2;
            case DOWN -> 3;
            case SOUTH -> 4;
            case NORTH -> 5;
        };
    }
}
