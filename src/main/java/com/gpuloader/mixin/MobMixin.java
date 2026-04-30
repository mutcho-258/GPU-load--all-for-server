package com.gpuloader.mixin;

import com.gpuloader.Config;
import com.gpuloader.core.MobAiManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GPU補間方式:
 * - バニラAIは常に実行される (GoalSelector等のスキップなし)
 * - serverAiStep() の最後で、GPU結果があればバニラの移動先に微調整を加える
 */
@Mixin(Mob.class)
public abstract class MobMixin {
    @Shadow(aliases = "f_21523_")
    protected MoveControl moveControl;

    @Inject(method = "serverAiStep", at = @At("TAIL"))
    private void onServerAiStepTail(CallbackInfo ci) {
        Mob mob = (Mob) (Object) this;
        if (mob.level().isClientSide()) return;
        if (!Config.gpuAi) return;

        // GPU結果とバニラ意図の両方が必要
        MobAiManager.MobResult gpuResult = MobAiManager.getResult(mob.getId());
        MobAiManager.VanillaIntent vanillaIntent = MobAiManager.getVanillaIntent(mob.getId());

        if (gpuResult == null || vanillaIntent == null) return;

        // バニラ意図が古すぎる場合はスキップ (5tick以上前)
        long age = mob.level().getGameTime() - vanillaIntent.capturedTick;
        if (age > 5) return;

        // ブレンド: バニラの移動先にGPUの微調整を適用
        Vec3 blendedTarget = MobAiManager.blendWithGPU(mob, vanillaIntent, gpuResult);
        if (blendedTarget == null) return; // blendFactor=0 or mob stationary

        // バニラの速度を維持しつつ、方向のみ微調整
        this.moveControl.setWantedPosition(blendedTarget.x, blendedTarget.y, blendedTarget.z, vanillaIntent.speed);
    }
}
