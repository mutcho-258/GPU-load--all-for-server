package com.gpuloader.gl;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientWindowHelper {
    public static long getMainWindowHandle() {
        return Minecraft.getInstance().getWindow().getWindow();
    }
}
