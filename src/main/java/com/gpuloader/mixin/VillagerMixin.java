package com.gpuloader.mixin;

import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * GPU補間方式ではバニラのBrain AIは常に実行される。
 * このMixinは互換性のため残すが、リダイレクトは行わない。
 * (MobMixin で全Mob共通の TAIL ブレンドが適用される)
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {
    // Brain.tick() のリダイレクトを削除: バニラAIは常に動く
}
