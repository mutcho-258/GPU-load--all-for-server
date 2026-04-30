package com.gpuloader.core;

import com.gpuloader.Config;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * GPU補助チャンクフェイスカリングの管理クラス。（クライアント専用）
 *
 * 役割:
 *  1. セクション（16x16x16）の不透明度フラグを収集してGPUタスクを発行
 *  2. GPU計算済みのフェイスマスクをキャッシュ（TTL: 5秒）
 *  3. SectionRenderMixin からフェイスマスクを参照するAPIを提供
 *
 * キー設計: sectionKey = (chunkX << 20) | (sectionY + 64) << 8) | chunkZ & 0xFF
 * （Minecraft 1.20の世界高度: -64〜319, セクションY: -4〜19）
 *
 * FMLClientSetupEvent から init() を呼ぶこと。
 */
public class MeshCullManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** キャッシュTTL: 5秒（ms） */
    private static final long CACHE_TTL_MS = 5_000L;

    /** セクションキャッシュ */
    private static final ConcurrentHashMap<Long, CachedSection> CACHE = new ConcurrentHashMap<>();

    /** 計算リクエスト中のキー集合（重複リクエスト防止） */
    private static final ConcurrentHashMap<Long, Boolean> PENDING = new ConcurrentHashMap<>();

    private static boolean initialized = false;

    // ---- キャッシュエントリ ----

    public static class CachedSection {
        /** GPU計算済みのフェイスマスク配列 [4096]: 各ブロックの6面表示フラグ */
        public final int[] faceMasks;
        public final long createdAt;

        public CachedSection(int[] faceMasks) {
            this.faceMasks = faceMasks;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean isExpired(long now) {
            return (now - createdAt) > CACHE_TTL_MS;
        }
    }

    // ---- 初期化 ----

    /** FMLClientSetupEvent から呼ぶ */
    public static void init() {
        if (initialized) return;
        initialized = true;
        LOGGER.info("[GPU Loader] MeshCullManager initialized (client-only).");
    }

    // ---- キー生成 ----

    /**
     * チャンク座標とセクションY座標からキャッシュキーを生成する。
     *
     * @param chunkX  チャンクX座標
     * @param sectionY セクションY座標（-4〜19）
     * @param chunkZ  チャンクZ座標
     */
    public static long makeSectionKey(int chunkX, int sectionY, int chunkZ) {
        // chunkX: 24bit, sectionY+64: 8bit (0〜83), chunkZ: 24bit
        // long: 64bit に収まるよう設計
        return ((long)(chunkX & 0xFFFFFF) << 32)
             | ((long)((sectionY + 64) & 0xFF) << 24)
             | ((long)(chunkZ & 0xFFFFFF));
    }

    // ---- GPU計算リクエスト ----

    /**
     * セクションのフェイスカリング計算をGPUに非同期リクエストする。
     * 既にキャッシュ済みまたはリクエスト中の場合はスキップ。
     *
     * @param chunkX   チャンクX
     * @param sectionY セクションY（-4〜19）
     * @param chunkZ   チャンクZ
     * @param section  LevelChunkSection（不透明度フラグ収集用）
     */
    public static void requestCull(int chunkX, int sectionY, int chunkZ, LevelChunkSection section) {
        if (!initialized || !Config.chunkMesh || !Config.enableAll) return;

        long key = makeSectionKey(chunkX, sectionY, chunkZ);

        // キャッシュが有効なら不要
        CachedSection cached = CACHE.get(key);
        if (cached != null) return;

        // 既にリクエスト中なら不要
        if (PENDING.putIfAbsent(key, Boolean.TRUE) != null) return;

        // 不透明度フラグを収集してGPUタスクを発行
        int[] opacityFlags = collectOpacityFlags(section);

        com.gpuloader.core.GPUComputeManager.submitRenderTask(new com.gpuloader.gl.MeshCullTask(key, opacityFlags));
    }

    /**
     * LevelChunkSection からブロックの不透明度フラグを収集する。
     * Y-major (&に16x16x16) 順でビットパックして128 uintsに収める。
     *
     * @param section LevelChunkSection
     * @return 128個の int（4096ビットのビットマスク）
     */
    private static int[] collectOpacityFlags(LevelChunkSection section) {
        int[] flags = BufferPool.acquireOpacityBuffer();
        java.util.Arrays.fill(flags, 0);

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int blockIdx = y * 256 + z * 16 + x;
                    boolean opaque = section.getBlockState(x, y, z).canOcclude();

                    if (opaque) {
                        int wordIdx = blockIdx / 32;
                        int bitIdx  = blockIdx % 32;
                        flags[wordIdx] |= (1 << bitIdx);
                    }
                }
            }
        }

        return flags;
    }

    // ---- 結果の保存（MeshCullTask から呼ばれる） ----

    /**
     * GPU計算完了後、MeshCullTask からコールバックされる。
     */
    public static void storeCullResult(long sectionKey, int[] faceMasks) {
        CACHE.put(sectionKey, new CachedSection(faceMasks));
        PENDING.remove(sectionKey);
    }

    // ---- SectionRenderMixin からの参照API ----

    /**
     * 指定ブロックの指定面が描画すべきかどうかを返す。
     *
     * @param sectionKey makeSectionKey() で生成したキー
     * @param localX     セクション内ローカルX (0-15)
     * @param localY     セクション内ローカルY (0-15)
     * @param localZ     セクション内ローカルZ (0-15)
     * @param face       面インデックス (0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z)
     * @return true=描画すべき, false=スキップ可能。キャッシュ未存在時はtrue（安全側）
     */
    public static boolean isFaceVisible(long sectionKey, int localX, int localY, int localZ, int face) {
        CachedSection cached = CACHE.get(sectionKey);
        if (cached == null) {
            return true; // キャッシュなし → 安全のため描画する
        }

        int blockIdx = localY * 256 + localZ * 16 + localX;
        if (blockIdx < 0 || blockIdx >= cached.faceMasks.length) return true;

        return (cached.faceMasks[blockIdx] & (1 << face)) != 0;
    }

    /**
     * 指定セクションのフェイスマスクが既にキャッシュされているか確認する。
     */
    public static boolean isCached(long sectionKey) {
        CachedSection cached = CACHE.get(sectionKey);
        return cached != null;
    }

    // ---- キャッシュ管理 ----

    /**
     * 期限切れのキャッシュエントリを削除する。
     * GPUMod の RenderTickEvent などから定期的に呼ぶ。
     */
    public static void cleanupExpiredCache() {
        long now = System.currentTimeMillis();
        CACHE.entrySet().removeIf(e -> {
            boolean expired = e.getValue().isExpired(now);
            if (expired) {
                // 期限切れの配列をプールに戻す
                BufferPool.releaseMeshBuffer(e.getValue().faceMasks);
            }
            return expired;
        });
    }

    /** シャットダウン時に全キャッシュをクリアする。 */
    public static void shutdown() {
        CACHE.clear();
        PENDING.clear();
        initialized = false;
        LOGGER.info("[GPU Loader] MeshCullManager shut down.");
    }

    /** デバッグ用: 現在のキャッシュエントリ数を返す */
    public static int getCacheSize() {
        return CACHE.size();
    }
}
