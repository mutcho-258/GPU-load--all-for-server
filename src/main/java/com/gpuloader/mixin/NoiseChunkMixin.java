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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow(aliases = {"firstCellX", "f_209148_", "m_209162_"})
    private int firstCellX;

    @Shadow(aliases = {"firstCellZ", "f_209149_", "m_209163_"})
    private int firstCellZ;

    @Shadow(aliases = {"cellWidth", "f_209170_"})
    private int cellWidth;

    @Shadow(aliases = {"cellStartBlockX", "f_209150_"})
    private int cellStartBlockX;

    @Shadow(aliases = {"inCellX", "f_209153_"})
    private int inCellX;

    @Shadow(aliases = {"cellCountXZ", "f_188718_"})
    private int cellCountXZ;

    @Shadow(aliases = {"cellStartBlockZ", "f_209152_"})
    private int cellStartBlockZ;

    @Shadow(aliases = {"inCellZ", "f_209155_"})
    private int inCellZ;

    @Shadow(aliases = {"arrayInterpolationCounter", "f_209157_"})
    private long arrayInterpolationCounter;

    @Shadow(aliases = {"sliceFillingContextProvider", "f_209159_"})
    private DensityFunction.ContextProvider sliceFillingContextProvider;

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

        // Only configure global terrain height for full chunk generation
        if (cellCountX >= 4) {
            try {
                net.minecraft.world.level.levelgen.synth.NormalNoise contNoise = randomState.getOrCreateNoise(net.minecraft.world.level.levelgen.Noises.CONTINENTALNESS);
                if (contNoise != null) {
                    net.minecraft.world.level.levelgen.synth.PerlinNoise firstPerlin = null;
                    for (java.lang.reflect.Field f : net.minecraft.world.level.levelgen.synth.NormalNoise.class.getDeclaredFields()) {
                        if (net.minecraft.world.level.levelgen.synth.PerlinNoise.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            firstPerlin = (net.minecraft.world.level.levelgen.synth.PerlinNoise) f.get(contNoise);
                            if (firstPerlin != null) break;
                        }
                    }
                    if (firstPerlin != null) {
                        net.minecraft.world.level.levelgen.synth.ImprovedNoise improved = firstPerlin.getOctaveNoise(0);
                        if (improved != null) {
                            byte[] pBytes = null;
                            for (java.lang.reflect.Field pf : net.minecraft.world.level.levelgen.synth.ImprovedNoise.class.getDeclaredFields()) {
                                if (pf.getType() == byte[].class) {
                                    pf.setAccessible(true);
                                    pBytes = (byte[]) pf.get(improved);
                                    if (pBytes != null && pBytes.length >= 256) break;
                                }
                            }
                            if (pBytes != null) {
                                com.gpuloader.core.NoiseBatchManager.updatePermutationFromVanilla(pBytes);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                com.gpuloader.core.NoiseBatchManager.updatePermutation(randomState.hashCode());
            }
            NoiseBatchManager.setHeight(cellCountY + 1);
        }

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
            NoiseType identifiedType = identifyWrappedFunction(filler, router);

            if (identifiedType != null) {
                IDENTIFICATION_CACHE.put(filler, identifiedType);
                accessor.setGpuIndex(identifiedType.getIndex());
            }
        }
    }

    /**
     * Tries to identify the function type.
     * Note: NoiseChunk.interpolators only contain functions for voxel filling (finalDensity, noodle caves, ore veins).
     * Climate noises (temperature, continents, etc.) are NOT in interpolators.
     * To prevent noodle caves or ore veins from being corrupted into surface walls or cave biomes,
     * we strictly match ONLY the terrain density function (DENSITY). All others are left to CPU hybrid execution.
     */
    @Unique
    private NoiseType identifyWrappedFunction(DensityFunction filler, NoiseRouter router) {
        if (filler == null) return null;

        // Guard against cave and ore vein interpolators
        String className = filler.getClass().getSimpleName();
        String fullStr = filler.toString().toLowerCase();
        if (fullStr.contains("noodle") || fullStr.contains("ore_vein") || fullStr.contains("cave")
                || fullStr.contains("pillar") || fullStr.contains("spaghetti")
                || className.contains("RangeChoice")) {
            return null; // Let CPU compute cave and ore vein noodles accurately
        }

        // Direct router match for terrain final density
        if (filler == router.finalDensity()) return NoiseType.DENSITY;

        // Check if it wraps finalDensity or sloped_cheese
        if (isTerrainDensity(filler, router, 0)) {
            return NoiseType.DENSITY;
        }

        return null;
    }

    @Unique
    private boolean isTerrainDensity(DensityFunction func, NoiseRouter router, int depth) {
        if (func == null || depth > 8) return false;

        if (func == router.finalDensity()) return true;

        String className = func.getClass().getSimpleName();
        if (className.contains("BlendDensity") || className.contains("SlopedCheese")
                || className.contains("FinalDensity") || className.contains("Base3DNoise")) {
            return true;
        }

        if (func instanceof DensityFunctions.MarkerOrMarked marker) {
            return isTerrainDensity(marker.wrapped(), router, depth + 1);
        }

        if (func instanceof DensityFunctions.HolderHolder holder) {
            return isTerrainDensity(holder.function().value(), router, depth + 1);
        }

        try {
            java.lang.reflect.Method inputMethod = func.getClass().getMethod("input");
            Object inputObj = inputMethod.invoke(func);
            if (inputObj instanceof DensityFunction innerFunc) {
                return isTerrainDensity(innerFunc, router, depth + 1);
            }
        } catch (Exception ignored) {
        }

        try {
            java.lang.reflect.Method wrappedMethod = func.getClass().getMethod("wrapped");
            Object wrappedObj = wrappedMethod.invoke(func);
            if (wrappedObj instanceof DensityFunction innerFunc) {
                return isTerrainDensity(innerFunc, router, depth + 1);
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    @Inject(method = {"fillSlice", "m_209220_"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void onFillSlice(boolean isSlice0, int cellX, CallbackInfo ci) {
        if (!com.gpuloader.GPUMod.initialized || !Config.terrainNoise) {
            return;
        }

        // Only offload full chunk generation (cellCountXZ >= 4).
        // Single-column sampling (e.g. iterateNoiseColumn with cellCountXZ == 1 for structure placement)
        // is very cheap on CPU and shouldn't trigger GPU chunk batching.
        if (this.cellCountXZ < 4) {
            return;
        }

        // Local X index inside the chunk (0..cellCountXZ, e.g. 0..4)
        int localCellX = cellX - this.firstCellX;
        if (localCellX < 0 || localCellX > this.cellCountXZ) {
            return;
        }

        int chunkX = Math.floorDiv(this.firstCellX, 4);
        int chunkZ = Math.floorDiv(this.firstCellZ, 4);
        
        // Cache request across slices for the same chunk
        if (currentGpuFuture == null || chunkX != lastRequestedX || chunkZ != lastRequestedZ) {
            currentGpuFuture = com.gpuloader.core.NoiseBatchManager.requestChunk(chunkX, chunkZ);
            lastRequestedX = chunkX;
            lastRequestedZ = chunkZ;
        }

        if (currentGpuFuture == null) return;

        java.nio.FloatBuffer gpuData = null;
        try {
            // Wait up to 200ms for GPU compute batch to complete
            gpuData = currentGpuFuture.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Fallback to full CPU if GPU timed out or failed
            trackCpuFallback();
            return;
        }

        if (gpuData == null) {
            trackCpuFallback();
            return;
        }

        // Apply GPU data to handled interpolators
        boolean allHandled = true;
        List<NoiseChunk.NoiseInterpolator> unhandled = null;
        for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
            NoiseInterpolatorAccessor accessor = (NoiseInterpolatorAccessor) interpolator;
            if (accessor.getGpuIndex() != -1) {
                accessor.setSliceFromGpu(gpuData, isSlice0, localCellX);
            } else {
                allHandled = false;
                if (unhandled == null) unhandled = new java.util.ArrayList<>();
                unhandled.add(interpolator);
            }
        }

        if (allHandled) {
            ci.cancel();
            return;
        }

        // Hybrid mode: GPU handled terrain & climate noise;
        // CPU handles ONLY the unhandled interpolators (e.g. ore veins / noodle caves)
        this.cellStartBlockX = cellX * this.cellWidth;
        this.inCellX = 0;
        for (int zIndex = 0; zIndex < this.cellCountXZ + 1; ++zIndex) {
            int cellZ = this.firstCellZ + zIndex;
            this.cellStartBlockZ = cellZ * this.cellWidth;
            this.inCellZ = 0;
            this.arrayInterpolationCounter++;
            for (NoiseChunk.NoiseInterpolator interpolator : unhandled) {
                NoiseInterpolatorAccessor acc = (NoiseInterpolatorAccessor) interpolator;
                double[] slice = acc.getSliceArray(isSlice0, zIndex);
                if (slice != null && this.sliceFillingContextProvider != null) {
                    interpolator.fillArray(slice, this.sliceFillingContextProvider);
                }
            }
        }
        this.arrayInterpolationCounter++;

        reportUnhandledInterpolators(unhandled);
        ci.cancel();
    }

    @Unique
    private static void trackCpuFallback() {
        com.gpuloader.core.NoiseBatchManager.trackCpuFallback();
        cpuFallbackSlices++;
        cpuFallbackSlices10m++;
        long now = System.currentTimeMillis();
        
        if (cpuFallbackSlices >= 160) { // ~32 chunks (32 * 5 slices)
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
                LOGGER.info("[GPU Terrain] Stats (last 10m): {} chunks fell back to CPU", cpuFallbackSlices10m / 5);
            }
            lastCpuFallbackLog10m = now;
            cpuFallbackSlices10m = 0;
        }
    }

    @Unique
    private void reportUnhandledInterpolators(List<NoiseChunk.NoiseInterpolator> unhandled) {
        if (unhandled == null) return;
        for (NoiseChunk.NoiseInterpolator interpolator : unhandled) {
            DensityFunction filler = ((com.gpuloader.core.NoiseInterpolatorAccessor) interpolator).getNoiseFiller();
            if (filler != null) {
                String typeName = filler.getClass().getSimpleName();
                com.gpuloader.core.NoiseBatchManager.reportUnhandledType(typeName);
            }
        }
    }
}
