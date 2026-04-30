package com.gpuloader.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * GPUで計算されたバイオームIDをチャンク座標をキーにしてキャッシュするクラス。
 * MultiNoiseBiomeSourceMixin から参照される。
 *
 * 座標系:
 * - キー: チャンク座標 (chunkX, chunkZ)
 * - 値: int[5 * height * 5] のバイオームID配列
 * - インデックス: y * (5 * 5) + localZ * 5 + localX
 *   - localX/Z: クォート座標からの相対位置 (0..4)
 */
public class BiomeResultCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ConcurrentHashMap<Long, BiomeCacheEntry> CACHE = new ConcurrentHashMap<>();
    private static int cachedHeight = 33;

    private static volatile net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry = null;
    private static volatile net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] biomeHolderArray = null;

    @SuppressWarnings("unchecked")
    public static void setBiomeRegistry(net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> registry) {
        biomeRegistry = registry;
        if (registry != null) {
            int maxId = 0;
            for (var biome : registry) {
                int id = registry.getId(biome);
                if (id > maxId) maxId = id;
            }
            biomeHolderArray = new net.minecraft.core.Holder[maxId + 1];
            for (var biome : registry) {
                int id = registry.getId(biome);
                biomeHolderArray[id] = registry.getHolder(id).orElse(null);
            }
        } else {
            biomeHolderArray = null;
        }
    }

    public static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiomeHolder(int biomeId) {
        if (biomeHolderArray != null && biomeId >= 0 && biomeId < biomeHolderArray.length) {
            return biomeHolderArray[biomeId];
        }
        if (biomeRegistry != null) {
             return biomeRegistry.getHolder(biomeId).orElse(null);
        }
        return null;
    }

    public static net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> getBiomeRegistry() {
        return biomeRegistry;
    }

    public static class BiomeCacheEntry {
        public final int[] biomeIds;
        public final int height;
        public long lastAccessTime;

        public BiomeCacheEntry(int[] biomeIds, int height) {
            this.biomeIds = biomeIds;
            this.height = height;
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * GPUで計算されたバイオームIDを格納する。
     *
     * @param chunkX チャンクX座標
     * @param chunkZ チャンクZ座標
     * @param biomeIds バイオームID配列 (5 * height * 5)
     * @param height 高さサンプル数
     */
    public static void put(int chunkX, int chunkZ, int[] biomeIds, int height) {
        CACHE.put(key(chunkX, chunkZ), new BiomeCacheEntry(biomeIds, height));
        cachedHeight = height;
    }

    /**
     * 指定されたクォート座標のバイオームIDを取得する。
     *
     * @param quartX クォートX座標 (ブロック座標 / 4)
     * @param quartY クォートY座標
     * @param quartZ クォートZ座標
     * @return バイオームID。キャッシュミスの場合 -1
     */
    public static int getBiomeId(int quartX, int quartY, int quartZ) {
        // クォート座標からチャンク座標を計算
        int chunkX = quartX >> 2;
        int chunkZ = quartZ >> 2;

        BiomeCacheEntry entry = CACHE.get(key(chunkX, chunkZ));
        if (entry == null) return -1;

        // ローカル座標を計算 (0..4)
        int localX = quartX - (chunkX << 2);
        int localZ = quartZ - (chunkZ << 2);
        int localY = quartY;

        // 範囲チェック
        if (localX < 0 || localX >= 5 || localZ < 0 || localZ >= 5
                || localY < 0 || localY >= entry.height) {
            return -1;
        }

        int index = localY * (5 * 5) + localZ * 5 + localX;
        if (index < 0 || index >= entry.biomeIds.length) return -1;

        return entry.biomeIds[index];
    }

    /**
     * 古いエントリを削除する。
     */
    public static void cleanup() {
        long now = System.currentTimeMillis();
        CACHE.entrySet().removeIf(e -> (now - e.getValue().lastAccessTime) > 30000);
    }

    public static void clear() {
        CACHE.clear();
        setBiomeRegistry(null);
    }

    public static int size() {
        return CACHE.size();
    }
}
