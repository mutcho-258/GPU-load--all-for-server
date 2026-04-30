package com.gpuloader.mixin;

import com.gpuloader.Config;
import com.gpuloader.core.MobAiManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * バニラの MoveControl.setWantedPosition() を監視し、
 * バニラAIが決めた移動先をキャプチャする。
 * バニラの動作そのものには一切干渉しない。
 */
@Mixin(MoveControl.class)
public abstract class MoveControlMixin {

    @Shadow(aliases = "f_24981_") @Final protected Mob mob;

    @Inject(method = "setWantedPosition(DDDD)V", at = @At("HEAD"))
    private void captureVanillaIntent(double x, double y, double z, double speed, CallbackInfo ci) {
        if (!Config.gpuAi) return;
        if (this.mob == null || this.mob.level().isClientSide()) return;

        MobAiManager.captureVanillaIntent(
            this.mob.getId(), x, y, z, speed,
            this.mob.level().getGameTime()
        );
    }
}
