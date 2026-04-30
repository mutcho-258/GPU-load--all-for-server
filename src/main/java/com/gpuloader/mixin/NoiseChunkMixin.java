package com.gpuloader.mixin;

import com.gpuloader.Config;
import com.gpuloader.core.NoiseBatchManager;
import com.gpuloader.core.NoiseInterpolatorAccessor;
import com.gpuloader.core.NoiseType;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow(aliases = "m_209162_")
    private int firstCellX;

    @Shadow(aliases = "m_209163_")
    private int firstCellZ;

    @Shadow
    @Final
    private List<NoiseChunk.NoiseInterpolator> interpolators;

    private java.util.concurrent.CompletableFuture<java.nio.FloatBuffer> currentGpuFuture = null;
    private int lastRequestedX = Integer.MAX_VALUE;
    private int lastRequestedZ = Integer.MAX_VALUE;

    private static int cpuFallbackSlices = 0;
    private static long lastCpuFallbackLog = 0;
    
    private static int cpuFallbackSlices10m = 0;
    private static long lastCpuFallbackLog10m = 0;

    private static final Map<DensityFunction, NoiseType> IDENTIFICATION_CACHE = Collections
            .synchronizedMap(new WeakHashMap<>());

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(int cellCountY, RandomState randomState, int cellCountX, int cellCountZ,
            NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifier,
            NoiseGeneratorSettings generatorSettings, Aquifer.FluidPicker fluidPicker,
            Blender blender, CallbackInfo ci) {

        com.gpuloader.core.NoiseBatchManager.updatePermutation(randomState.hashCode());
        NoiseBatchManager.setHeight(cellCountY + 1);
        NoiseRouter router = randomState.router();

        for (NoiseChunk.NoiseInterpolator interpolator : this.interpolators) {
            NoiseInterpolatorAccessor accessor = (NoiseInterpolatorAccessor) interpolator;
            DensityFunction filler = accessor.getNoiseFiller();

            if (filler == null)
                continue;

            // Check cache first to avoid slow re-identification
            NoiseType cachedType = IDENTIFICATION_CACHE.get(filler);
            if (cachedType != null) {
                accessor.setGpuIndex(cachedType.getIndex());
                continue;
            }

            // Identification logic
            NoiseType identifiedType = null;
            if (filler == router.finalDensity())
                identifiedType = NoiseType.DENSITY;
            else if (filler == router.temperature())
                identifiedType = NoiseType.TEMPERATURE;
            else if (filler == router.vegetation())
                identifiedType = NoiseType.HUMIDITY;
            else if (filler == router.continents())
                identifiedType = NoiseType.CONTINENTALNESS;
            else if (filler == router.erosion())
                identifiedType = NoiseType.EROSION;
            else if (filler == router.ridges())
                identifiedType = NoiseType.WEIRDNESS;
            else if (filler == router.depth())
                identifiedType = NoiseType.DEPTH;

            // Fallback for wrapped functions (BOP compatibility)
            if (identifiedType == null) {
                identifiedType = identifyWrappedFunction(filler, router);
            }

            if (identifiedType != null) {
                IDENTIFICATION_CACHE.put(filler, identifiedType);
                accessor.setGpuIndex(identifiedType.getIndex());
            }
        }
    }

    /**
     * Tries to identify the function type by looking at its structure or class name
     * without using the very slow toString() method.
     */
    private NoiseType identifyWrappedFunction(DensityFunction filler, NoiseRouter router) {
        // For BOP and other mods, we check if the class name or a lightweight property
        // matches
        String className = filler.getClass().getName();

        // BOP identification (example)
        if (className.contains("biomesoplenty")) {
            // If it's a BOP wrapper, we could try to unwrap it if we had the API,
            // but for now we'll use a very limited toString() or just ignore.
            // Actually, let's try a very simple string check ONLY for the class name.
            if (className.contains("Temperature"))
                return NoiseType.TEMPERATURE;
            if (className.contains("Vegetation"))
                return NoiseType.HUMIDITY;
        }

        return null;
    }

    @Inject(method = {"fillSlice", "m_209193_", "m_224370_", "m_209241_", "m_209191_", "m_207207_", "m_209220_"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void onFillSlice(boolean flag, int z, CallbackInfo ci) {
        if (!com.gpuloader.GPUMod.initialized || !Config.terrainNoise) {
            return;
        }
        
        // Diagnostic log
        // LOGGER.debug("GPU Loader: onFillSlice called for z={}", z);

        // Check if all interpolators are handled by GPU
        boolean allHandled = true;
        List<NoiseChunk.NoiseInterpolator> unhandled = null;
        
        for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
            if (((com.gpuloader.core.NoiseInterpolatorAccessor) interpolator).getGpuIndex() == -1) {
                allHandled = false;
                if (unhandled == null) unhandled = new java.util.ArrayList<>();
                unhandled.add(interpolator);
            }
        }

        int chunkX = firstCellX / 4;
        int chunkZ = firstCellZ / 4;
        
        // 同一チャンクの複数スライス(z)で何度もリクエストや get() を呼ぶのを避けるためキャッシュ
        if (currentGpuFuture == null || chunkX != lastRequestedX || chunkZ != lastRequestedZ) {
            currentGpuFuture = com.gpuloader.core.NoiseBatchManager.requestChunk(chunkX, chunkZ);
            lastRequestedX = chunkX;
            lastRequestedZ = chunkZ;
        }

        if (currentGpuFuture == null) return;

        java.nio.FloatBuffer gpuData = null;
        try {
            // 既に完了していれば即座に、そうでなければ最大200ms待機
            gpuData = currentGpuFuture.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (gpuData != null) {
                applyGpuDataToInterpolators(gpuData, z);
                
                if (allHandled) {
                    ci.cancel();
                } else {
                    trackCpuFallback();
                    reportUnhandledInterpolators(unhandled);
                }
            } else {
                trackCpuFallback();
            }
        } catch (Exception e) {
            trackCpuFallback();
            // Fallback
        }
    }

    private static void trackCpuFallback() {
        cpuFallbackSlices++;
        cpuFallbackSlices10m++;
        long now = System.currentTimeMillis();
        
        if (cpuFallbackSlices >= 1056) { // ~32 chunks (32 * 33 slices)
            if (now - lastCpuFallbackLog > 5000) { // Limit log spam
                if (com.gpuloader.Config.debugTerrainNoise) {
                    LOGGER.info("[GPU Terrain] WARNING: Terrain noise generation is falling back to CPU! (Avg ~32 chunks processed by CPU)");
                }
                lastCpuFallbackLog = now;
            }
            cpuFallbackSlices = 0;
        }

        if (lastCpuFallbackLog10m == 0) lastCpuFallbackLog10m = now;
        if (now - lastCpuFallbackLog10m >= 600000) {
            if (cpuFallbackSlices10m > 0) {
                LOGGER.info("[GPU Terrain] Stats (last 10m): {} chunks fell back to CPU", cpuFallbackSlices10m / 33);
            }
            lastCpuFallbackLog10m = now;
            cpuFallbackSlices10m = 0;
        }
    }

    private static final java.util.Set<String> REPORTED_TYPES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private void reportUnhandledInterpolators(List<NoiseChunk.NoiseInterpolator> unhandled) {
        for (NoiseChunk.NoiseInterpolator interpolator : unhandled) {
            DensityFunction filler = ((com.gpuloader.core.NoiseInterpolatorAccessor) interpolator).getNoiseFiller();
            if (filler != null) {
                String typeName = filler.getClass().getSimpleName();
                if (REPORTED_TYPES.add(typeName)) {
                    LOGGER.info("[GPU Loader] Unhandled Ocean/Terrain Noise type: {} (Falling back to CPU for this chunk)", typeName);
                }
            }
        }
    }

    private void applyGpuDataToInterpolators(java.nio.FloatBuffer gpuData, int z) {
        for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
            NoiseInterpolatorAccessor accessor = (NoiseInterpolatorAccessor) interpolator;
            if (accessor.getGpuIndex() != -1) {
                accessor.setSlice1FromFloat(gpuData, z);
            }
        }
    }
}
