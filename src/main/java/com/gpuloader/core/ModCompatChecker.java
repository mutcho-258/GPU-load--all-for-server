package com.gpuloader.core;

import com.gpuloader.Config;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.List;

/**
 * 起動時に競合MODを検知し、必要に応じてチャンクメッシュ機能を自動無効化するクラス。
 *
 * 対象: SectionRenderDispatcher など描画コアクラスに干渉する可能性があるMOD。
 * 検知した場合は Config.chunkMesh を false に強制設定して警告ログを出力する。
 *
 * FMLCommonSetupEvent から呼び出すこと（全環境で競合チェックを行いたいため）。
 */
public class ModCompatChecker {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * チャンクメッシュ機能と競合する既知のMOD IDリスト。
     * SectionRenderDispatcher + BufferBuilder 周辺に Mixin / ASM を適用する MOD が対象。
     */
    private static final List<String> CONFLICTING_MODS = List.of(
        "embeddium",       // Forge向け高速描画（Sodium派生）
        "rubidium",        // Forge向け高速描画（旧Sodium派生）
        "sodium",          // Fabric向け（Forgeには基本入らないが念のため）
        "iris",            // Fabric向けシェーダーMOD
        "oculus",          // Forge向けシェーダーMOD（Iris派生）
        "optifine",        // OptiFine（描画パイプラインを全面書き換え）
        "nvidium",         // NVIDIA向け高速描画
        "immediatelyfast", // 即時描画最適化
        "canary",          // チャンク生成最適化（一部Mixin競合）
        "starlight",       // ライティング最適化（描画スレッドに関与）
        "biomesoplenty",   // 地形生成MOD（ノイズ設定が特殊）
        "terrablender"     // バイオーム管理（ノイズ生成に干渉）
    );

    /**
     * 競合MODを検知し、問題があれば ChunkMesh 機能を無効化する。
     * FMLCommonSetupEvent 内から呼ぶこと。
     *
     * @return 競合MODが見つかった場合 true
     */
    public static boolean checkAndDisableIfNeeded() {
        for (String modId : CONFLICTING_MODS) {
            if (ModList.get().isLoaded(modId)) {
                LOGGER.warn("╔════════════════════════════════════════════╗");
                LOGGER.warn("║  [GPU Loader] 競合MOD検知: '{}'", modId);
                LOGGER.warn("║  ChunkMesh GPU補助機能を自動無効化しました。");
                LOGGER.warn("║  ノイズ生成GPU加速は引き続き有効です。");
                LOGGER.warn("╚════════════════════════════════════════════╝");

                // Config のキャッシュ変数を直接上書き（設定ファイルは変更しない）
                if (!modId.equals("biomesoplenty") && !modId.equals("terrablender")) {
                    Config.chunkMesh = false;
                }
                return true;
            }
        }

        LOGGER.info("[GPU Loader] 競合MODは検知されませんでした。ChunkMesh機能は有効です。");
        return false;
    }

    /**
     * 現在の状態を返す（デバッグ・コマンド表示用）。
     */
    public static String getStatusMessage() {
        for (String modId : CONFLICTING_MODS) {
            if (ModList.get().isLoaded(modId)) {
                return "§c無効 (競合MOD: " + modId + ")";
            }
        }
        return Config.chunkMesh ? "§a有効" : "§7無効 (Config)";
    }
}
