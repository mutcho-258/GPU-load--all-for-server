package com.gpuloader.core;

import com.gpuloader.Config;
import com.gpuloader.gl.GPUComputeThread;
import com.gpuloader.gl.LightComputeTask;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * チャンク生成・ロード時の一括光源伝播を管理するマネージャー。
 * バニラのセクションデータから不透明度と自己放射光を抽出し、
 * GPU Compute Shader による並列セル・オートマトン計算を行って一括流し込みを行います。
 */
public class LightBatchManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    // 統計・診断用ステータス
    public static long totalGpuChunks = 0;
    public static long totalFallbackChunks = 0;
    public static long totalGpuTimeMs = 0;
    public static double lastGpuElapsedMs = 0;
    public static String lastStatus = "未実行 (待機中)";
    public static String lastFallbackReason = "なし";

    /**
     * LevelLightEngine.propagateLightSources から呼ばれ、
     * バニラの重い CPU BFS キュー処理を GPU Compute Shader に置き換える。
     *
     * @return true: GPU処理成功（バニラのCPU処理をキャンセル可能）
     *         false: 失敗（バニラのCPU処理へフォールバック）
     */
    public static boolean processChunkSynchronous(LevelLightEngine lightEngine, ChunkAccess chunk) {
        if (!Config.gpuLighting) {
            lastFallbackReason = "コンフィグ (gpuloader-server.toml) で無効化中";
            lastStatus = "無効";
            return false;
        }

        ChunkPos chunkPos = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        int sectionCount = sections.length;
        int height = sectionCount * 16;
        int gridX = 18;
        int gridZ = 18;
        int gridY = height + 2;
        int totalVoxels = gridX * gridZ * gridY;

        int[] blockData = new int[totalVoxels];

        long startTime = System.nanoTime();

        // チャンク内部 (16xheightx16) のデータをセクションから抽出
        int minSection = chunk.getMinSection();
        for (int s = 0; s < sectionCount; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) {
                continue; // 空気のみの場合は opacity 0, emission 0 のまま
            }

            int sectionBaseY = s * 16;
            for (int y = 0; y < 16; y++) {
                int paddedY = (sectionBaseY + y) + 1;

                for (int z = 0; z < 16; z++) {
                    int paddedZ = z + 1;

                    for (int x = 0; x < 16; x++) {
                        int paddedX = x + 1;

                        BlockState state = section.getBlockState(x, y, z);
                        int opacity = state.getLightBlock(chunk, BlockPos.ZERO);
                        int emission = state.getLightEmission(chunk, BlockPos.ZERO);

                        int voxelIdx = (paddedY * gridX * gridZ) + (paddedZ * gridX) + paddedX;
                        blockData[voxelIdx] = ((opacity & 0x0F) << 16) | (emission & 0x0F);
                    }
                }
            }
        }

        // 天井 (paddedY = gridY - 1) は空光の侵入元となるため、透明（opacity 0）を保証
        // 地底 (paddedY = 0) は不透明（opacity 15）
        for (int z = 0; z < gridZ; z++) {
            for (int x = 0; x < gridX; x++) {
                int bottomIdx = (0 * gridX * gridZ) + (z * gridX) + x;
                blockData[bottomIdx] = (15 << 16); // Opacity 15

                int topIdx = ((gridY - 1) * gridX * gridZ) + (z * gridX) + x;
                blockData[topIdx] = 0; // Opacity 0 (Sky)
            }
        }

        try {
            // GPU Compute Task を作成して実行
            LightComputeTask task = new LightComputeTask(blockData, sectionCount);
            GPUComputeThread.submitTask(task);

            // GPU での計算完了を待機（通常 1ms 以内）
            LightComputeTask.LightResult lightResult = task.getFuture().get(3, TimeUnit.SECONDS);

            long elapsedNs = System.nanoTime() - startTime;
            lastGpuElapsedMs = (double) elapsedNs / 1_000_000.0;
            totalGpuChunks++;
            totalGpuTimeMs += (long) lastGpuElapsedMs;
            lastStatus = String.format("GPU正常動作: chunk %s (%.2f ms)", chunkPos, lastGpuElapsedMs);

            if (totalGpuChunks % 100 == 0 || totalGpuChunks == 1) {
                LOGGER.info("[GPU Light] Successfully processed chunk {} on GPU ({} ms, Total: {} chunks, Avg: {} ms)",
                        chunkPos, (long) lastGpuElapsedMs, totalGpuChunks, (double) totalGpuTimeMs / totalGpuChunks);
            }

            // セクションごとの光量データを LevelLightEngine に一括反映
            for (int s = 0; s < sectionCount; s++) {
                int sectionY = minSection + s;
                SectionPos sectionPos = SectionPos.of(chunkPos, sectionY);

                byte[] blockBytes = lightResult.blockLightSections[s];
                byte[] skyBytes = lightResult.skyLightSections[s];

                lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, new DataLayer(blockBytes));
                lightEngine.queueSectionData(LightLayer.SKY, sectionPos, new DataLayer(skyBytes));
            }

            return true;
        } catch (Throwable t) {
            totalFallbackChunks++;
            lastFallbackReason = t.getClass().getSimpleName() + ": " + (t.getMessage() != null ? t.getMessage() : "null");
            lastStatus = "CPUフォールバック: " + lastFallbackReason;
            LOGGER.error("[GPU Light] GPU lighting failed for chunk {}, falling back to CPU", chunkPos, t);
            return false;
        }
    }
}
