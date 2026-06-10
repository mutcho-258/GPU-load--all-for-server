package com.gpuloader.gl;

import com.gpuloader.Config;
import com.gpuloader.core.GPUTask;
import com.gpuloader.core.MobAiManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GPU Compute Shader による Mob AI 計算タスク。
 *
 * <p><b>非同期パイプライン方式:</b></p>
 * <pre>
 * Frame N:   [Upload N → Dispatch N → Fence N]
 * Frame N+1: [Upload N+1 → Dispatch N+1 → Fence N+1]  |  [Read N → Apply N]
 * </pre>
 *
 * <p>Persistent Mapped ダブルバッファ ({@link com.gpuloader.api.IPersistentBuffer}) を使用し、
 * CPU-GPU 間のデータ転送を完全にノンブロッキング化。</p>
 */
public class MobAiTask extends GPUTask<float[]> {
    private static int program = -1;

    // Uniform location キャッシュ
    private static int loc_time = -1;
    private static int loc_mob_count = -1;

    public static final int MAX_MOBS = 1024;
    private static final int INPUT_STRIDE = 32 * Float.BYTES;   // 128
    private static final int OUTPUT_STRIDE = 4 * Float.BYTES;   // 16

    /** Persistent Mapped バッファ（シングルトン） */
    private static com.gpuloader.api.IPersistentBuffer persistentBuffer;

    /** 非同期結果の保留キュー */
    private static final Queue<PendingAiBatch> PENDING_QUEUE = new ConcurrentLinkedQueue<>();

    private final float[] uploadData;
    private final int[] entityIds;
    private final int count;
    private final long gameTime;

    /**
     * GPU計算完了待ちのバッチ情報。
     */
    private static class PendingAiBatch {
        final int[] entityIds;
        final int mobCount;
        float[] results;

        PendingAiBatch(int mobCount, int[] entityIds) {
            this.entityIds = entityIds;
            this.mobCount = mobCount;
        }
    }

    public MobAiTask(float[] uploadData, int[] entityIds, int count, long gameTime) {
        this.uploadData = uploadData;
        this.entityIds = entityIds;
        this.count = count;
        this.gameTime = gameTime;
    }

    @Override
    public void execute() {
        if (program == -1) {
            String source = GLShaderManager.loadShaderSource("/assets/gpuloader/shaders/mob_ai.comp");
            program = GLShaderManager.createComputeProgram("mob_ai", source);
            if (program == -1) return;

            // Uniform location をキャッシュ
            loc_time = GL20.glGetUniformLocation(program, "time");
            loc_mob_count = GL20.glGetUniformLocation(program, "mob_count");
        }

        // 保留中のバッチの完了チェック
        processReadyBatches();

        // Persistent Buffer の遅延初期化
        if (persistentBuffer == null) {
            persistentBuffer = com.gpuloader.core.GPUComputeManager.getInstance().createPersistentBuffer(
                    MAX_MOBS * INPUT_STRIDE, MAX_MOBS * OUTPUT_STRIDE);
            persistentBuffer.init();
        }

        if (!persistentBuffer.isInitialized()) {
            return;
        }

        int actualCount = Math.min(this.count, MAX_MOBS);

        // ---- Upload: Persistent Mapped Buffer に直接書き込み（glBufferData 不要）----
        ByteBuffer input = persistentBuffer.getWriteInputBuffer();
        if (input == null) return;

        FloatBuffer floatInput = input.asFloatBuffer();
        floatInput.put(this.uploadData, 0, actualCount * 32);

        // position を書き込んだ分だけ進めた状態のまま（flip不要：Coherent で自動同期）
        input.position(actualCount * 128); // 32 floats = 128 bytes
        input.flip();

        // ---- Dispatch ----
        org.lwjgl.opengl.GL20.glUseProgram(program);
        org.lwjgl.opengl.GL20.glUniform1f(loc_time, (float) (gameTime % 24000L));
        org.lwjgl.opengl.GL20.glUniform1i(loc_mob_count, actualCount);

        persistentBuffer.bindInputBuffer(0);
        persistentBuffer.bindOutputBuffer(1);

        org.lwjgl.opengl.GL43.glDispatchCompute((actualCount + 63) / 64, 1, 1);
        org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // ---- Fence + Swap: 結果は次フレームで回収 ----
        persistentBuffer.insertOutputFence();
        persistentBuffer.swap();

        PENDING_QUEUE.add(new PendingAiBatch(actualCount, entityIds));

        // このタスクの Future はすぐには完了しない。
        // processReadyBatches() で前フレームの結果が回収されたときに MobAiManager へ直接格納する。
        result.complete(null);
    }

    /**
     * 保留中のバッチの完了をチェックし、結果を MobAiManager に格納する。
     * GPUMod の tick ループ or GPUComputeThread から定期的に呼ばれる。
     */
    public static void processReadyBatches() {
        if (persistentBuffer == null || !persistentBuffer.isInitialized()) return;

        while (true) {
            PendingAiBatch pending = PENDING_QUEUE.peek();
            if (pending == null) break;

            ByteBuffer mapped = persistentBuffer.tryReadOutput(0L);
            if (mapped != null) {
                PENDING_QUEUE.poll();

                FloatBuffer floats = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer();

                for (int i = 0; i < pending.mobCount; i++) {
                    if (i * 4 + 3 >= floats.limit()) break;
                    MobAiManager.MobResult res = new MobAiManager.MobResult();
                    res.dirX = floats.get(i * 4);
                    res.dirY = floats.get(i * 4 + 1);
                    res.dirZ = floats.get(i * 4 + 2);
                    res.actionHint = (int) floats.get(i * 4 + 3);

                    // 直接 MobAiManager へ格納
                    MobAiManager.storeResult(pending.entityIds[i], res);
                }

                persistentBuffer.finishRead();
            } else {
                break;
            }
        }
    }

    /**
     * 全リソースをクリーンアップする。
     * ワールド退出時やMod リロード時に呼ぶ。
     */
    public static void cleanup() {
        PENDING_QUEUE.clear();
        if (persistentBuffer != null) {
            persistentBuffer.cleanup();
            persistentBuffer = null;
        }
        program = -1;
        loc_time = -1;
        loc_mob_count = -1;
    }
}
