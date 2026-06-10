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
 * - GPU計算を行うTick (isGpuTick) のみ CPU AI (serverAiStep) を通常実行して移動意図をキャプチャ。
 * - それ以外のTickは CPU AI をスキップし、前回の意図にGPU補正をブレンドした目標値を設定することでCPU負荷を大幅に削減。
 */
@Mixin(Mob.class)
public abstract class MobMixin {
    @Shadow(aliases = "f_21523_")
    protected MoveControl moveControl;

    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void onServerAiStepHead(CallbackInfo ci) {
        Mob mob = (Mob) (Object) this;
        if (mob.level().isClientSide()) return;
        if (!Config.gpuAi) return;

        long gameTime = mob.level().getGameTime();

        // GPU計算外のTickは、CPU AIの実行をスキップ
        if (!MobAiManager.isGpuTick(gameTime)) {
            MobAiManager.MobResult gpuResult = MobAiManager.getResult(mob.getId());
            MobAiManager.VanillaIntent vanillaIntent = MobAiManager.getVanillaIntent(mob.getId());

            if (gpuResult != null && vanillaIntent != null) {
                // 意図が古すぎないかチェック (間隔 + 1Tick以内)
                long age = gameTime - vanillaIntent.capturedTick;
                if (age <= Config.aiComputeInterval + 1) {
                    // 前回意図とGPU補正のブレンド
                    Vec3 blendedTarget = MobAiManager.blendWithGPU(mob, vanillaIntent, gpuResult);
                    if (blendedTarget != null) {
                        this.moveControl.setWantedPosition(blendedTarget.x, blendedTarget.y, blendedTarget.z, vanillaIntent.speed);
                    }
                    // CPU AI (serverAiStep) の実行をブロック/キャンセル
                    ci.cancel();
                }
            }
        }
    }

    @Inject(method = "serverAiStep", at = @At("TAIL"))
    private void onServerAiStepTail(CallbackInfo ci) {
        Mob mob = (Mob) (Object) this;
        if (mob.level().isClientSide()) return;
        if (!Config.gpuAi) return;

        long gameTime = mob.level().getGameTime();

        // CPU AIを実行したTickのみ、最終調整を行う
        if (MobAiManager.isGpuTick(gameTime)) {
            MobAiManager.MobResult gpuResult = MobAiManager.getResult(mob.getId());
            MobAiManager.VanillaIntent vanillaIntent = MobAiManager.getVanillaIntent(mob.getId());

            if (gpuResult == null || vanillaIntent == null) return;

            long age = gameTime - vanillaIntent.capturedTick;
            if (age > Config.aiComputeInterval + 1) return;

            Vec3 blendedTarget = MobAiManager.blendWithGPU(mob, vanillaIntent, gpuResult);
            if (blendedTarget == null) return;

            this.moveControl.setWantedPosition(blendedTarget.x, blendedTarget.y, blendedTarget.z, vanillaIntent.speed);
        }
    }
}
