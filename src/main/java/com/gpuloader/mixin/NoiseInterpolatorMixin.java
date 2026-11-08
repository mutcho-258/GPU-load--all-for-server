package com.gpuloader.mixin;

import com.gpuloader.core.NoiseInterpolatorAccessor;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorMixin implements NoiseInterpolatorAccessor {

    @Shadow(aliases = {"slice0", "f_188828_"})
    private double[][] slice0;

    @Shadow(aliases = {"slice1", "f_188829_"})
    private double[][] slice1;

    @Shadow(aliases = {"noiseFiller", "f_188830_"})
    private net.minecraft.world.level.levelgen.DensityFunction noiseFiller;

    @Unique
    private int gpuIndex = -1;

    @Override
    public boolean shouldSubstitute() {
        if (!com.gpuloader.Config.terrainNoise)
            return false;
        if (noiseFiller == null)
            return false;

        String className = noiseFiller.getClass().getSimpleName();
        return className.contains("Noise") || className.contains("ShiftedNoise")
                || className.contains("Interpolated") || className.contains("RangeChoice")
                || className.contains("BlendDensity")
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
    public double[] getSliceArray(boolean isSlice0, int zIndex) {
        double[][] target = isSlice0 ? slice0 : slice1;
        if (target != null && zIndex >= 0 && zIndex < target.length) {
            return target[zIndex];
        }
        return null;
    }

    @Override
    public void setSliceFromGpu(java.nio.FloatBuffer data, boolean isSlice0, int localCellX) {
        if (gpuIndex == -1 || data == null)
            return;

        double[][] target = isSlice0 ? slice0 : slice1;
        if (target == null || target.length == 0 || target[0] == null)
            return;

        int depth = target.length;        // cellCountXZ + 1 (5 in full chunk)
        int height = target[0].length;    // cellCountY + 1 (33 in full chunk)
        int width = 5;                   // cellCountXZ + 1 (5 in full chunk)

        if (localCellX < 0 || localCellX >= width)
            return;

        int limit = data.limit();

        for (int z = 0; z < depth; z++) {
            double[] column = target[z];
            for (int y = 0; y < height; y++) {
                int voxel_id = y * (width * depth) + z * width + localCellX;
                int index = voxel_id * 7 + gpuIndex;
                if (index >= 0 && index < limit) {
                    column[y] = (double) data.get(index);
                }
            }
        }
    }
}
