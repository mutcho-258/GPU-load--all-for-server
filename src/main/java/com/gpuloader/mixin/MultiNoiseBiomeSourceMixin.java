package com.gpuloader.mixin;

import com.gpuloader.core.BiomeParameterExtractor;
import com.gpuloader.core.BiomeResultCache;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MultiNoiseBiomeSource.getNoiseBiome をインターセプトし、
 * GPUで事前計算されたバイオームIDがキャッシュに存在する場合はそれを返す。
 * キャッシュミスの場合はバニラ処理にフォールスルーする。
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {

    /**
     * getNoiseBiome をインターセプトして GPU キャッシュから返す。
     * getNoiseBiome はクォート座標 (ブロック座標/4) で呼ばれる。
     */
    @Inject(method = "getNoiseBiome", at = @At("HEAD"), cancellable = true, require = 0)
    private void onGetNoiseBiome(int quartX, int quartY, int quartZ,
                                  Climate.Sampler sampler,
                                  CallbackInfoReturnable<Holder<Biome>> cir) {
        if (!BiomeParameterExtractor.isExtracted()) return;

        int biomeId = BiomeResultCache.getBiomeId(quartX, quartY, quartZ);
        if (biomeId < 0) return; // Cache miss, fall through to vanilla

        // Resolve biome ID to Holder<Biome> using zero-allocation cache
        Holder<Biome> biome = BiomeResultCache.getBiomeHolder(biomeId);
        if (biome != null) {
            cir.setReturnValue(biome);
        }
    }
}
