package com.gpuloader.gl;

import com.gpuloader.Config;
import com.gpuloader.core.GPUTask;
import com.gpuloader.core.MobAiManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MobAiTask extends GPUTask<float[]> {
    private static int program = -1;
    private static int inputBuffer = -1;
    private static int outputBuffer = -1;

    private final List<Mob> mobsSnapshot;
    private final long gameTime;

    // 32 floats per mob = 128 bytes
    private static final int FLOATS_PER_MOB = 32;
    private static final int STRIDE_BYTES = FLOATS_PER_MOB * 4;

    public MobAiTask(List<Mob> snapshot, long gameTime) {
        this.mobsSnapshot = snapshot;
        this.gameTime = gameTime;
    }

    @Override
    public void execute() {
        if (program == -1) {
            String source = GLShaderManager.loadShaderSource("/assets/gpuloader/shaders/mob_ai.comp");
            program = GLShaderManager.createComputeProgram("mob_ai", source);
            inputBuffer = GL15.glGenBuffers();
            outputBuffer = GL15.glGenBuffers();
        }

        int count = mobsSnapshot.size();
        ByteBuffer input = BufferUtils.createByteBuffer(count * STRIDE_BYTES).order(ByteOrder.nativeOrder());

        for (Mob mob : mobsSnapshot) {
            MobAiManager.CachedContext ctx = MobAiManager.getContext(mob.getId());
            MobAiManager.Personality p = MobAiManager.getPersonality(mob);

            // [0-3] pos.xyz + entityId
            input.putFloat((float) mob.getX());
            input.putFloat((float) mob.getY());
            input.putFloat((float) mob.getZ());
            input.putFloat((float) mob.getId());

            MobAiManager.VanillaIntent intent = MobAiManager.getVanillaIntent(mob.getId());
            Vec3 mobPos = mob.position();

            // [4-7] vanillaIntent: direction.xyz, speed
            if (intent != null) {
                Vec3 dir = intent.directionFrom(mobPos).normalize();
                input.putFloat((float) dir.x);
                input.putFloat((float) dir.y);
                input.putFloat((float) dir.z);
                input.putFloat((float) intent.speed);
            } else {
                input.putFloat(0.0f);
                input.putFloat(0.0f);
                input.putFloat(0.0f);
                input.putFloat(0.0f);
            }

            // [8-11] danger.xyz + dangerRange
            input.putFloat((float) ctx.danger.x);
            input.putFloat((float) ctx.danger.y);
            input.putFloat((float) ctx.danger.z);
            input.putFloat((float) Config.aiDangerRange);

            // [12-15] personality base: poiWeight, dangerWeight, idleWeight, speed
            input.putFloat(p.poiWeight());
            input.putFloat(p.dangerWeight());
            input.putFloat(p.idleWeight());
            input.putFloat(p.speed());

            // [16-19] personality extended: aggroRange, fleeThreshold, packBehavior, nightActivity
            input.putFloat(p.aggroRange());
            input.putFloat(p.fleeThreshold());
            input.putFloat(p.packBehavior());
            input.putFloat(p.nightActivity());

            // [20-23] dynamic state: healthRatio, dayTime, lightLevel, lastHurtAge
            input.putFloat(ctx.healthRatio);
            input.putFloat(ctx.dayTime);
            input.putFloat(ctx.lightLevel);
            input.putFloat(ctx.lastHurtAge);

            // [24-27] more state: nearbyAllyCount, inWater, hasTarget, mobTypeId
            input.putFloat(ctx.nearbyAllyCount);
            input.putFloat(ctx.inWater);
            input.putFloat(ctx.hasTarget);
            input.putFloat((float) p.mobTypeId());

            // [28-31] reserved padding
            input.putFloat(0.0f);
            input.putFloat(0.0f);
            input.putFloat(0.0f);
            input.putFloat(0.0f);
        }
        input.flip();

        GL20.glUseProgram(program);
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "time"), (float) gameTime);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "mob_count"), count);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, inputBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, input, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, inputBuffer);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, outputBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) count * 16, GL15.GL_STREAM_READ);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, outputBuffer);

        GL43.glDispatchCompute((count + 63) / 64, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);

        FloatBuffer output = BufferUtils.createFloatBuffer(count * 4);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, outputBuffer);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, output);

        float[] resArray = new float[count * 4];
        output.get(resArray);
        result.complete(resArray);
    }
}
