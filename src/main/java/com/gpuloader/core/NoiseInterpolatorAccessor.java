package com.gpuloader.core;

import net.minecraft.world.level.levelgen.DensityFunction;
import java.nio.FloatBuffer;

public interface NoiseInterpolatorAccessor {
    void setSlice1FromFloat(FloatBuffer data, int zSliceIndex);

    boolean shouldSubstitute();

    void setGpuIndex(int index);

    int getGpuIndex();

    DensityFunction getNoiseFiller();
}
