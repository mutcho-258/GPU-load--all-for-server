package com.gpuloader.core;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.List;

/**
 * ワールド読み込み時に MultiNoiseBiomeSource からバイオームの気候パラメータを抽出し、
 * GPU SSBO用の float[] 配列にパックするクラス。
 *
 * データ構造 (1エントリ = 16 floats, std430 alignment):
 * [0]  temp_min      [1]  temp_max
 * [2]  humidity_min  [3]  humidity_max
 * [4]  cont_min      [5]  cont_max
 * [6]  erosion_min   [7]  erosion_max
 * [8]  weird_min     [9]  weird_max
 * [10] depth_min     [11] depth_max
 * [12] offset
 * [13] biome_id (intBitsToFloat packed)
 * [14] padding       [15] padding
 */
public class BiomeParameterExtractor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLOATS_PER_ENTRY = 16;

    private static float[] biomeData = null;
    private static int biomeCount = 0;
    private static boolean extracted = false;

    /**
     * ServerLevel からバイオームパラメータを抽出する。
     * ワールド読み込み時（LevelEvent.Load）に呼び出す。
     */
    @SuppressWarnings("unchecked")
    public static boolean extract(ServerLevel level) {
        try {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            BiomeSource biomeSource = generator.getBiomeSource();

            if (!(biomeSource instanceof MultiNoiseBiomeSource multiNoise)) {
                LOGGER.warn("[GPU Loader] BiomeSource is not MultiNoiseBiomeSource ({}), GPU biome disabled for this world.",
                        biomeSource.getClass().getSimpleName());
                extracted = false;
                biomeData = null;
                biomeCount = 0;
                return false;
            }

            // Access the private 'parameters' field via reflection
            // In official mappings (1.20.1): MultiNoiseBiomeSource.parameters
            Climate.ParameterList<Holder<Biome>> parameters = getParameters(multiNoise);
            if (parameters == null) {
                LOGGER.error("[GPU Loader] Failed to extract biome parameters (reflection failed).");
                extracted = false;
                return false;
            }

            Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

            // Get all parameter points
            List<com.mojang.datafixers.util.Pair<Climate.ParameterPoint, Holder<Biome>>> entries = parameters.values();
            biomeCount = entries.size();
            biomeData = new float[biomeCount * FLOATS_PER_ENTRY];

            int idx = 0;
            for (var entry : entries) {
                Climate.ParameterPoint point = entry.getFirst();
                Holder<Biome> biomeHolder = entry.getSecond();

                int biomeId = biomeRegistry.getId(biomeHolder.value());

                // Convert quantized long values to float (Minecraft uses value * 10000)
                biomeData[idx + 0]  = unquantize(point.temperature().min());
                biomeData[idx + 1]  = unquantize(point.temperature().max());
                biomeData[idx + 2]  = unquantize(point.humidity().min());
                biomeData[idx + 3]  = unquantize(point.humidity().max());
                biomeData[idx + 4]  = unquantize(point.continentalness().min());
                biomeData[idx + 5]  = unquantize(point.continentalness().max());
                biomeData[idx + 6]  = unquantize(point.erosion().min());
                biomeData[idx + 7]  = unquantize(point.erosion().max());
                biomeData[idx + 8]  = unquantize(point.weirdness().min());
                biomeData[idx + 9]  = unquantize(point.weirdness().max());
                biomeData[idx + 10] = unquantize(point.depth().min());
                biomeData[idx + 11] = unquantize(point.depth().max());
                biomeData[idx + 12] = unquantize(point.offset());
                biomeData[idx + 13] = Float.intBitsToFloat(biomeId);
                biomeData[idx + 14] = 0.0f; // padding
                biomeData[idx + 15] = 0.0f; // padding

                idx += FLOATS_PER_ENTRY;
            }

            extracted = true;
            LOGGER.info("[GPU Loader] Extracted {} biome entries for GPU biome determination.", biomeCount);

            // Pass registry to the cache for biome ID -> Holder<Biome> resolution
            com.gpuloader.core.BiomeResultCache.setBiomeRegistry(biomeRegistry);

            return true;

        } catch (Exception e) {
            LOGGER.error("[GPU Loader] Failed to extract biome parameters", e);
            extracted = false;
            biomeData = null;
            biomeCount = 0;
            return false;
        }
    }

    /**
     * Reflection で MultiNoiseBiomeSource の parameters メソッドにアクセス。
     * （フィールドは Either<Climate.ParameterList, ...> のため、直接取得できない）
     */
    @SuppressWarnings("unchecked")
    private static Climate.ParameterList<Holder<Biome>> getParameters(MultiNoiseBiomeSource source) {
        try {
            // Try known method names (official mappings + obfuscated)
            for (String methodName : new String[]{"parameters", "m_48512_", "m_204269_"}) {
                try {
                    java.lang.reflect.Method method = MultiNoiseBiomeSource.class.getDeclaredMethod(methodName);
                    method.setAccessible(true);
                    return (Climate.ParameterList<Holder<Biome>>) method.invoke(source);
                } catch (NoSuchMethodException ignored) {
                }
            }

            // Fallback: search by return type
            for (java.lang.reflect.Method method : MultiNoiseBiomeSource.class.getDeclaredMethods()) {
                if (Climate.ParameterList.class.isAssignableFrom(method.getReturnType()) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return (Climate.ParameterList<Holder<Biome>>) method.invoke(source);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[GPU Loader] Reflection failed for MultiNoiseBiomeSource.parameters()", e);
        }
        return null;
    }

    /**
     * Minecraft's quantized long to float conversion.
     * Internal representation: long = (float * 10000)
     */
    private static float unquantize(long value) {
        return value / 10000.0f;
    }

    public static float[] getBiomeData() {
        return biomeData;
    }

    public static int getBiomeCount() {
        return biomeCount;
    }

    public static boolean isExtracted() {
        return extracted;
    }

    public static void clear() {
        biomeData = null;
        biomeCount = 0;
        extracted = false;
    }
}
