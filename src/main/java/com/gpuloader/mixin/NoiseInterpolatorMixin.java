package com.gpuloader.mixin;

import com.gpuloader.core.NoiseInterpolatorAccessor;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorMixin implements NoiseInterpolatorAccessor {

    @Shadow(aliases = "m_209214_")
    private double[][] slice1;

    @Shadow(aliases = "m_209215_")
    private net.minecraft.world.level.levelgen.DensityFunction noiseFiller;

    @Unique
    private int gpuIndex = -1;

    @Override
    public boolean shouldSubstitute() {
        if (noiseFiller == null)
            return false;

        String className = noiseFiller.getClass().getSimpleName();
        return className.contains("Noise") || className.contains("ShiftedNoise")
                || className.contains("Interpolated") || className.contains("RangeChoice")
                || gpuIndex != -1;
    }

    @Override
    public void setGpuIndex(int index) {
        this.gpuIndex = index;
    }

    @Override
    public int getGpuIndex() {
        return this.gpuIndex;
    }

    @Override
    public DensityFunction getNoiseFiller() {
        return this.noiseFiller;
    }

    @Override
    public void setSlice1FromFloat(java.nio.FloatBuffer data, int zSliceIndex) {
        if (slice1 == null || gpuIndex == -1)
            return;

        int width = slice1.length;
        int height = slice1[0].length;
        int depth = 5;
        int pointsPerChunk = width * height * depth;

        int offset = gpuIndex * pointsPerChunk;

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int index = offset + (y * (width * depth) + zSliceIndex * width + x);
                if (index < data.capacity()) {
                    slice1[x][y] = (double) data.get(index);
                }
            }
        }
    }
}
