package com.gpuloader.command;

import com.gpuloader.Config;
import com.gpuloader.core.LightBatchManager;
import com.gpuloader.core.MobAiManager;
import com.gpuloader.gl.EntityMdiManager;
import com.gpuloader.gl.GLShaderManager;
import com.gpuloader.gl.NoiseComputeTask;
import com.gpuloader.core.NoiseBatchManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * GPU Loader のモジュール稼働状況・フォールバック状態をリアルタイム診断するコマンド。
 */
public class GPUCommand {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gpustat")
                .executes(context -> {
                    showOverview(context.getSource());
                    return 1;
                })
                .then(Commands.argument("module", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("overview");
                            builder.suggest("light");
                            builder.suggest("noise");
                            builder.suggest("ai");
                            builder.suggest("render");
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String module = StringArgumentType.getString(context, "module").toLowerCase();
                            switch (module) {
                                case "light" -> showLightStats(context.getSource());
                                case "noise" -> showNoiseStats(context.getSource());
                                case "ai" -> showAiStats(context.getSource());
                                case "render", "mdi" -> showRenderStats(context.getSource());
                                default -> showOverview(context.getSource());
                            }
                            return 1;
                        })
                )
        );

        dispatcher.register(Commands.literal("gpudebug")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("§6[GPU Loader] §fLogging diagnostic scan to console..."), false);
                    LOGGER.info("=== [GPU Loader] DIAGNOSTIC DUMP ===");
                    LOGGER.info("Light Status: {}, GPU Chunks: {}, Fallback Chunks: {}, Last Reason: {}",
                            LightBatchManager.lastStatus, LightBatchManager.totalGpuChunks,
                            LightBatchManager.totalFallbackChunks, LightBatchManager.lastFallbackReason);
                    LOGGER.info("Noise GPU Chunks: {}, Fallback Est: {}, Unhandled Types: {}",
                            NoiseComputeTask.getTerrainTotalChunks(), NoiseBatchManager.getCpuFallbackChunkEstimate(),
                            NoiseBatchManager.getReportedUnhandledTypes());
                    LOGGER.info("Mob AI Tracked: {}, Results: {}",
                            MobAiManager.getTrackedMobCount(), MobAiManager.getComputedResultCount());
                    LOGGER.info("=== [GPU Loader] DUMP END ===");
                    return 1;
                }));
    }

    private static void showOverview(CommandSourceStack source) {
        boolean gl = GLShaderManager.isSupported();
        String renderer = GLShaderManager.getGpuRenderer();
        boolean integrated = GLShaderManager.isIntegratedGPU();

        source.sendSuccess(() -> Component.literal("§6====== [GPU Loader 診断ステータス] ======"), false);
        source.sendSuccess(() -> Component.literal("§7GPU: §f" + renderer + (integrated ? " §c[内蔵GPU]" : " §a[専用GPU]")), false);
        source.sendSuccess(() -> Component.literal("§7Compute Shaders: " + (gl ? "§aREADY" : "§cNOT SUPPORTED")), false);
        source.sendSuccess(() -> Component.literal(""), false);

        // 1. 光源伝播
        String lightState;
        if (!Config.gpuLighting) {
            lightState = "§7[無効 (Config)]";
        } else if (LightBatchManager.totalFallbackChunks > 0 && LightBatchManager.totalGpuChunks == 0) {
            lightState = "§c[CPUフォールバック中]";
        } else if (LightBatchManager.totalGpuChunks > 0) {
            lightState = "§a[GPU稼働中]";
        } else {
            lightState = "§e[待機中 (0件)]";
        }
        double lightAvg = LightBatchManager.totalGpuChunks == 0 ? 0 : (double) LightBatchManager.totalGpuTimeMs / LightBatchManager.totalGpuChunks;
        source.sendSuccess(() -> Component.literal("§e1. 光源伝播 (Light): " + lightState), false);
        source.sendSuccess(() -> Component.literal(String.format("   §7GPU: §a%d§7 チャンク (平均 §b%.2f§7 ms) | CPUフォールバック: §c%d§7 チャンク",
                LightBatchManager.totalGpuChunks, lightAvg, LightBatchManager.totalFallbackChunks)), false);
        if (!"なし".equals(LightBatchManager.lastFallbackReason)) {
            source.sendSuccess(() -> Component.literal("   §c直近のフォールバック理由: §e" + LightBatchManager.lastFallbackReason), false);
        }

        // 2. 地形生成ノイズ
        String noiseState;
        if (!Config.terrainNoise) {
            noiseState = "§7[無効 (Config)]";
        } else if (NoiseComputeTask.getTerrainTotalChunks() > 0) {
            noiseState = "§a[GPU稼働中]";
        } else {
            noiseState = "§e[待機中]";
        }
        int noiseFallbacks = NoiseBatchManager.getCpuFallbackChunkEstimate();
        source.sendSuccess(() -> Component.literal("§e2. 地形ノイズ (Terrain Noise): " + noiseState), false);
        source.sendSuccess(() -> Component.literal(String.format("   §7GPU: §a%d§7 チャンク (平均 §b%.2f§7 ms) | CPUフォールバック推定: §c%d§7 チャンク",
                NoiseComputeTask.getTerrainTotalChunks(), NoiseComputeTask.getAverageLatencyMs(), noiseFallbacks)), false);
        Set<String> unhandled = NoiseBatchManager.getReportedUnhandledTypes();
        if (!unhandled.isEmpty()) {
            source.sendSuccess(() -> Component.literal("   §7未対応ノイズ型 (CPU移行要因): §6" + unhandled), false);
        }

        // 3. Mob AI
        String aiState = Config.gpuAi ? "§a[GPU稼働中]" : "§7[無効 (Config)]";
        source.sendSuccess(() -> Component.literal("§e3. Mob AI: " + aiState), false);
        source.sendSuccess(() -> Component.literal(String.format("   §7追跡中モブ: §a%d§7 体 | 計算済み結果: §a%d§7 件",
                MobAiManager.getTrackedMobCount(), MobAiManager.getComputedResultCount())), false);

        // 4. Entity MDI
        String mdiState = Config.entityMDI ? "§a[有効]" : "§7[無効 (Config)]";
        source.sendSuccess(() -> Component.literal("§e4. Entity MDI (描画最適化): " + mdiState), false);
        source.sendSuccess(() -> Component.literal(String.format("   §7直近フレーム描画: §a%d§7 体 (§b%d§7 バッチ)",
                EntityMdiManager.lastFrameRenderedEntities, EntityMdiManager.lastFrameBatches)), false);

        source.sendSuccess(() -> Component.literal("§6=========================================="), false);
        source.sendSuccess(() -> Component.literal("§7詳細コマンド: §f/gpustat <light|noise|ai|render>"), false);
    }

    private static void showLightStats(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6--- [GPU Loader: 光源伝播 詳細] ---"), false);
        source.sendSuccess(() -> Component.literal("§7設定有効化 (Config): " + (Config.gpuLighting ? "§a有効" : "§c無効")), false);
        source.sendSuccess(() -> Component.literal("§7シェーダー対応: " + (GLShaderManager.isSupported() ? "§a対応" : "§c非対応")), false);
        source.sendSuccess(() -> Component.literal("§7直近ステータス: §f" + LightBatchManager.lastStatus), false);
        source.sendSuccess(() -> Component.literal("§7直近GPU処理時間: §b" + String.format("%.2f ms", LightBatchManager.lastGpuElapsedMs)), false);
        source.sendSuccess(() -> Component.literal("§7累計GPU成功チャンク数: §a" + LightBatchManager.totalGpuChunks), false);
        source.sendSuccess(() -> Component.literal("§7累計CPUフォールバック数: §c" + LightBatchManager.totalFallbackChunks), false);
        source.sendSuccess(() -> Component.literal("§7直近フォールバック理由: §e" + LightBatchManager.lastFallbackReason), false);
    }

    private static void showNoiseStats(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6--- [GPU Loader: 地形生成ノイズ 詳細] ---"), false);
        source.sendSuccess(() -> Component.literal("§7設定有効化 (Config): " + (Config.terrainNoise ? "§a有効" : "§c無効")), false);
        source.sendSuccess(() -> Component.literal("§7累計GPUチャンク数: §a" + NoiseComputeTask.getTerrainTotalChunks()), false);
        source.sendSuccess(() -> Component.literal("§7累計GPUバッチ数: §a" + NoiseComputeTask.getTerrainBatchCount()), false);
        source.sendSuccess(() -> Component.literal("§7平均レイテンシ: §b" + String.format("%.2f ms", NoiseComputeTask.getAverageLatencyMs())), false);
        source.sendSuccess(() -> Component.literal("§7CPUフォールバック推定: §c" + NoiseBatchManager.getCpuFallbackChunkEstimate() + " チャンク"), false);
        source.sendSuccess(() -> Component.literal("§7検知された未対応型: §6" + NoiseBatchManager.getReportedUnhandledTypes()), false);
    }

    private static void showAiStats(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6--- [GPU Loader: Mob AI 詳細] ---"), false);
        source.sendSuccess(() -> Component.literal("§7設定有効化 (Config): " + (Config.gpuAi ? "§a有効" : "§c無効")), false);
        source.sendSuccess(() -> Component.literal("§7追跡中エンティティ数: §a" + MobAiManager.getTrackedMobCount()), false);
        source.sendSuccess(() -> Component.literal("§7GPU計算結果保持数: §a" + MobAiManager.getComputedResultCount()), false);
    }

    private static void showRenderStats(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6--- [GPU Loader: 描画最適化 詳細] ---"), false);
        source.sendSuccess(() -> Component.literal("§7MDI設定有効化 (Config): " + (Config.entityMDI ? "§a有効" : "§c無効")), false);
        source.sendSuccess(() -> Component.literal("§7チャンクメッシュ設定: " + (Config.chunkMesh ? "§a有効" : "§c無効")), false);
        source.sendSuccess(() -> Component.literal("§7オクルージョンカリング: " + (Config.culling ? "§a有効" : "§c無効")), false);
        source.sendSuccess(() -> Component.literal("§7直近MDI描画エンティティ数: §a" + EntityMdiManager.lastFrameRenderedEntities), false);
        source.sendSuccess(() -> Component.literal("§7直近MDIバッチ数: §b" + EntityMdiManager.lastFrameBatches), false);
    }
}
