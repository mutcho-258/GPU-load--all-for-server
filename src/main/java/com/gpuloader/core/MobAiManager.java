package com.gpuloader.core;

import com.gpuloader.Config;
import com.gpuloader.gl.GPUComputeThread;
import com.gpuloader.gl.MobAiTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GPU Mob AI Manager — 補間方式
 *
 * バニラAIは毎tick動く。GPUは偶数tickでバニラの移動意図に
 * 環境要因(光・群れ・昼夜・HP)の微調整を加える。
 * blendFactor=0 なら完全バニラと同じ動きになる。
 */
@Mod.EventBusSubscriber(modid = "gpuloader")
public class MobAiManager {
    private static final Map<Integer, Mob> TRACKED_MOBS = new ConcurrentHashMap<>();
    private static final Map<Integer, MobResult> RESULTS = new ConcurrentHashMap<>();
    private static final Map<Integer, CachedContext> CONTEXT_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, VanillaIntent> VANILLA_INTENTS = new ConcurrentHashMap<>();

    private static int updateIndex = 0;

    private static long lastAiLogTime = 0;
    private static int aiBatchCount = 0;
    private static long aiTotalLatency = 0;
    private static int aiTotalMobs = 0;

    // ─── GPU result (adjustment vector) ───
    public static class MobResult {
        public float dirX, dirY, dirZ;
        public int actionHint; // 0=no change, 1=environmental, 2=pack, 3=alert
    }

    // ─── Vanilla AI's movement intent, captured from MoveControl ───
    public static class VanillaIntent {
        public double wantedX, wantedY, wantedZ;
        public double speed;
        public long capturedTick;

        public Vec3 directionFrom(Vec3 mobPos) {
            return new Vec3(wantedX - mobPos.x, wantedY - mobPos.y, wantedZ - mobPos.z);
        }
    }

    // ─── Environmental context for GPU ───
    public static class CachedContext {
        public Vec3 danger = new Vec3(0, -1000, 0);
        public float healthRatio = 1.0f;
        public float dayTime = 0.0f;
        public float lightLevel = 15.0f;
        public float lastHurtAge = 999.0f;
        public float nearbyAllyCount = 0.0f;
        public float inWater = 0.0f;
        public float hasTarget = 0.0f;
    }

    // ─── Personality: same struct for all, zeros disable features ───
    public static record Personality(
            float poiWeight, float dangerWeight, float idleWeight, float speed,
            float aggroRange, float fleeThreshold, float packBehavior, float nightActivity,
            int mobTypeId) {
    }

    // ─── Mob Type IDs ───
    public static final int TYPE_ZOMBIE = 1;
    public static final int TYPE_SKELETON = 2;
    public static final int TYPE_CREEPER = 3;
    public static final int TYPE_SPIDER = 4;
    public static final int TYPE_ENDERMAN = 5;
    public static final int TYPE_WITCH = 6;
    public static final int TYPE_BLAZE = 7;
    public static final int TYPE_SLIME = 8;
    public static final int TYPE_PHANTOM = 9;
    public static final int TYPE_VILLAGER = 10;
    public static final int TYPE_IRON_GOLEM = 11;
    public static final int TYPE_COW = 20;
    public static final int TYPE_CHICKEN = 21;
    public static final int TYPE_WOLF = 22;
    public static final int TYPE_CAT = 23;
    public static final int TYPE_HORSE = 24;
    public static final int TYPE_WATER = 30;
    public static final int TYPE_DROWNED = 31;
    public static final int TYPE_GUARDIAN = 32;
    public static final int TYPE_DEFAULT = 99;

    // ─── Events ───
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            TRACKED_MOBS.put(mob.getId(), mob);
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            int id = mob.getId();
            TRACKED_MOBS.remove(id);
            RESULTS.remove(id);
            CONTEXT_CACHE.remove(id);
            VANILLA_INTENTS.remove(id);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.START && !event.level.isClientSide()) {
            processTick(event.level);
        }
    }

    // ─── Called from MoveControlMixin to capture vanilla's wanted position ───
    public static void captureVanillaIntent(int entityId, double x, double y, double z, double speed, long gameTick) {
        VanillaIntent intent = VANILLA_INTENTS.computeIfAbsent(entityId, k -> new VanillaIntent());
        intent.wantedX = x;
        intent.wantedY = y;
        intent.wantedZ = z;
        intent.speed = speed;
        intent.capturedTick = gameTick;
    }

    // ─── Main tick ───
    public static void processTick(Level level) {
        if (!Config.gpuAi)
            return;

        List<Mob> snapshot = new ArrayList<>(TRACKED_MOBS.values());
        if (snapshot.isEmpty())
            return;

        int size = snapshot.size();
        int[] ids = new int[size];
        for (int i = 0; i < size; i++)
            ids[i] = snapshot.get(i).getId();

        // Update environmental context in batches
        for (int i = 0; i < Math.min(Config.aiUpdateBatch, size); i++) {
            updateIndex = (updateIndex + 1) % size;
            updateMobContext(snapshot.get(updateIndex), level);
        }

        long currentTime = level.getGameTime();

        // GPU compute on configured interval (偶数tick等)
        if (currentTime % Config.aiComputeInterval == 0) {
            long startTime = System.currentTimeMillis();
            MobAiTask task = new MobAiTask(snapshot, currentTime);
            GPUComputeThread.submitTask(task);

            task.result.thenAccept(data -> {
                long latency = System.currentTimeMillis() - startTime;
                aiBatchCount++;
                aiTotalLatency += latency;
                aiTotalMobs += ids.length;

                long now = System.currentTimeMillis();
                if (lastAiLogTime == 0) lastAiLogTime = now;
                if (now - lastAiLogTime >= 600000) { // 10 minutes
                    com.mojang.logging.LogUtils.getLogger().info(
                            "[GPU AI] Stats (last 10m): Avg {} mobs/batch, Avg Latency {}ms",
                            aiTotalMobs / aiBatchCount, aiTotalLatency / aiBatchCount);
                    lastAiLogTime = now;
                    aiBatchCount = 0;
                    aiTotalLatency = 0;
                    aiTotalMobs = 0;
                }

                for (int i = 0; i < ids.length; i++) {
                    if (i * 4 + 3 >= data.length)
                        break;
                    MobResult res = new MobResult();
                    res.dirX = data[i * 4];
                    res.dirY = data[i * 4 + 1];
                    res.dirZ = data[i * 4 + 2];
                    res.actionHint = (int) data[i * 4 + 3];
                    RESULTS.put(ids[i], res);
                }
            });
        }
    }

    // ─── Environment context gathering ───
    private static void updateMobContext(Mob mob, Level level) {
        CachedContext ctx = CONTEXT_CACHE.computeIfAbsent(mob.getId(), k -> new CachedContext());

        ctx.healthRatio = mob.getHealth() / Math.max(mob.getMaxHealth(), 1.0f);

        long rawTime = level.getDayTime() % 24000L;
        ctx.dayTime = rawTime / 24000.0f;

        BlockPos bp = mob.blockPosition();
        int blockLight = level.getBrightness(LightLayer.BLOCK, bp);
        int skyLight = level.getBrightness(LightLayer.SKY, bp);
        int skyDarken = level.getSkyDarken();
        ctx.lightLevel = Math.min(Math.max(blockLight, skyLight - skyDarken), 15);

        int hurtTick = mob.getLastHurtByMobTimestamp();
        ctx.lastHurtAge = hurtTick > 0 ? Math.min((float) (level.getGameTime() - hurtTick), 200.0f) : 999.0f;

        ctx.inWater = mob.isInWater() ? 1.0f : 0.0f;
        ctx.hasTarget = mob.getTarget() != null ? 1.0f : 0.0f;

        // Nearby danger detection
        double range = Config.aiDangerRange;
        AABB searchBox = mob.getBoundingBox().inflate(range);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, searchBox);

        int allyCount = 0;
        boolean foundDanger = false;

        for (LivingEntity e : entities) {
            if (e == mob)
                continue;
            if (e.getClass() == mob.getClass())
                allyCount++;

            if (!foundDanger) {
                // 危険源の検出 (バニラAIが処理するPOIとは別に、環境認識として)
                if (mob instanceof Animal && e instanceof Monster) {
                    ctx.danger = e.position();
                    foundDanger = true;
                } else if (mob instanceof Villager && e instanceof Monster) {
                    ctx.danger = e.position();
                    foundDanger = true;
                }
            }
        }

        ctx.nearbyAllyCount = (float) Math.min(allyCount, 10);
        if (!foundDanger) {
            ctx.danger = new Vec3(mob.getX(), mob.getY() - 1000, mob.getZ());
        }
    }

    // ─── Blend vanilla intent with GPU adjustment ───
    public static Vec3 blendWithGPU(Mob mob, VanillaIntent vanilla, MobResult gpu) {
        float blend = (float) Config.gpuBlendFactor;
        if (blend <= 0.005f)
            return null; // Pure vanilla

        Vec3 mobPos = mob.position();
        Vec3 vanillaDir = vanilla.directionFrom(mobPos);
        double vanillaLen = vanillaDir.length();
        if (vanillaLen < 0.5)
            return null; // Target is very close. Blending GPU vectors at this range causes wild angle changes (spinning).

        Vec3 vanillaNorm = vanillaDir.normalize();
        Vec3 gpuDir = new Vec3(gpu.dirX, gpu.dirY, gpu.dirZ);

        // GPU output is already a normalized adjustment vector.
        // Blend: (1-blend)*vanilla + blend*gpu, then re-scale to vanilla's magnitude
        Vec3 blended = vanillaNorm.scale(1.0 - blend).add(gpuDir.scale(blend));
        double blendLen = blended.length();
        if (blendLen < 0.001)
            return null;

        // Normalize and re-apply vanilla's distance magnitude
        blended = blended.normalize().scale(vanillaLen);

        return mobPos.add(blended);
    }

    // ─── Accessors ───
    public static MobResult getResult(int entityId) {
        return RESULTS.get(entityId);
    }

    public static VanillaIntent getVanillaIntent(int entityId) {
        return VANILLA_INTENTS.get(entityId);
    }

    public static CachedContext getContext(int id) {
        return CONTEXT_CACHE.getOrDefault(id, new CachedContext());
    }

    public static boolean isGpuTick(long gameTime) {
        return gameTime % Config.aiComputeInterval == 0;
    }

    // ─── Personality mapping ───
    public static Personality getPersonality(Mob mob) {
        if (mob instanceof Zombie && !(mob instanceof Drowned))
            return new Personality(1.8f, 0.1f, 0.3f, 0.5f, 40f, 0.0f, 0.6f, 1.0f, TYPE_ZOMBIE);
        if (mob instanceof Skeleton)
            return new Personality(1.5f, 0.3f, 0.2f, 0.6f, 15f, 0.2f, 0.0f, 1.0f, TYPE_SKELETON);
        if (mob instanceof Creeper)
            return new Personality(2.5f, 0.0f, 0.4f, 0.5f, 3f, 0.0f, 0.0f, 0.8f, TYPE_CREEPER);
        if (mob instanceof Spider)
            return new Personality(1.6f, 0.1f, 0.5f, 0.7f, 16f, 0.1f, 0.3f, 0.7f, TYPE_SPIDER);
        if (mob instanceof EnderMan)
            return new Personality(0.3f, 0.0f, 0.8f, 1.0f, 64f, 0.0f, 0.0f, 0.5f, TYPE_ENDERMAN);
        if (mob instanceof Witch)
            return new Personality(1.2f, 0.5f, 0.3f, 0.5f, 10f, 0.3f, 0.0f, 0.6f, TYPE_WITCH);
        if (mob instanceof Blaze)
            return new Personality(1.8f, 0.0f, 0.2f, 0.5f, 48f, 0.0f, 0.0f, 0.5f, TYPE_BLAZE);
        if (mob instanceof Slime)
            return new Personality(1.0f, 0.0f, 0.6f, 0.4f, 16f, 0.0f, 0.0f, 0.5f, TYPE_SLIME);
        if (mob instanceof Phantom)
            return new Personality(2.0f, 0.0f, 0.2f, 0.9f, 32f, 0.0f, 0.5f, 1.0f, TYPE_PHANTOM);
        if (mob instanceof Drowned)
            return new Personality(1.7f, 0.0f, 0.4f, 0.4f, 20f, 0.0f, 0.4f, 0.8f, TYPE_DROWNED);
        if (mob instanceof Guardian)
            return new Personality(1.8f, 0.0f, 0.3f, 0.5f, 16f, 0.0f, 0.3f, 0.5f, TYPE_GUARDIAN);
        if (mob instanceof Villager)
            return new Personality(0.8f, 2.0f, 0.3f, 0.5f, 0.0f, 0.5f, 0.8f, 0.0f, TYPE_VILLAGER);
        if (mob instanceof IronGolem)
            return new Personality(2.0f, 0.0f, 0.6f, 0.4f, 16f, 0.0f, 0.0f, 0.5f, TYPE_IRON_GOLEM);
        if (mob instanceof Wolf)
            return new Personality(1.0f, 0.3f, 0.5f, 0.7f, 12f, 0.2f, 0.9f, 0.4f, TYPE_WOLF);
        if (mob instanceof Cat || mob instanceof Ocelot)
            return new Personality(0.4f, 1.5f, 0.8f, 0.7f, 0.0f, 0.4f, 0.3f, 0.3f, TYPE_CAT);
        if (mob instanceof Chicken)
            return new Personality(0.3f, 1.8f, 1.0f, 0.5f, 0.0f, 0.2f, 0.5f, 0.1f, TYPE_CHICKEN);
        if (mob instanceof Horse)
            return new Personality(0.3f, 1.0f, 0.7f, 0.8f, 0.0f, 0.3f, 0.6f, 0.2f, TYPE_HORSE);
        if (mob instanceof WaterAnimal)
            return new Personality(0.2f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.5f, TYPE_WATER);
        if (mob instanceof Animal)
            return new Personality(0.5f, 1.5f, 0.9f, 0.4f, 0.0f, 0.3f, 0.7f, 0.2f, TYPE_COW);
        if (mob instanceof Monster)
            return new Personality(2.0f, 0.0f, 0.2f, 0.6f, 16f, 0.0f, 0.2f, 0.8f, TYPE_DEFAULT);
        return new Personality(0.5f, 1.2f, 0.8f, 0.4f, 0.0f, 0.0f, 0.5f, 0.5f, TYPE_DEFAULT);
    }
}
