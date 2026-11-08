package com.gpuloader.core;

import net.minecraft.world.level.levelgen.DensityFunction;
import java.nio.FloatBuffer;

public interface NoiseInterpolatorAccessor {
    void setSliceFromGpu(FloatBuffer data, boolean isSlice0, int localCellX);

    double[] getSliceArray(boolean isSlice0, int zIndex);

    boolean shouldSubstitute();

    void setGpuIndex(int index);

    int getGpuIndex();

    DensityFunction getNoiseFiller();
}
