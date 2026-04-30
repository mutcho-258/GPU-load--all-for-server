package com.gpuloader.gl;

import com.gpuloader.core.BiomeParameterExtractor;
import com.mojang.logging.LogUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * バイオームパラメータのSSBOを管理するクラス。
 * binding = 2: BiomeData (読み取り専用, バイオームエントリ配列)
 * binding = 3: BiomeOutput (書き込み専用, バイオームID出力)
 */
public class BiomeGPUBuffer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static int biomeDataBuffer = -1;
    private static int biomeOutputBuffer = -1;
    private static int currentBiomeCount = 0;
    private static boolean initialized = false;

    /**
     * BiomeParameterExtractorから抽出済みデータをSSBOにアップロードする。
     * レンダースレッドから呼び出すこと。
     */
    public static void uploadBiomeData() {
        if (!BiomeParameterExtractor.isExtracted()) {
            return;
        }

        float[] data = BiomeParameterExtractor.getBiomeData();
        int count = BiomeParameterExtractor.getBiomeCount();

        if (data == null || count == 0) {
            return;
        }

        // Create or update BiomeData SSBO (binding = 2)
        if (biomeDataBuffer == -1) {
            biomeDataBuffer = GL15.glGenBuffers();
        }

        // Buffer layout: [biome_count (as 4 bytes padding)] + [BiomeEntry[] data]
        // We prepend the count as a float for alignment, the shader reads biome_count as int
        int totalFloats = 4 + data.length; // 4 floats for header (count + 3 padding)
        FloatBuffer buf = BufferUtils.createFloatBuffer(totalFloats);

        // Header: biome_count as intBitsToFloat, then 3 padding floats
        buf.put(Float.intBitsToFloat(count));
        buf.put(0.0f);
        buf.put(0.0f);
        buf.put(0.0f);
        // Body: BiomeEntry data
        buf.put(data);
        buf.flip();

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, biomeDataBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, buf, GL15.GL_STATIC_DRAW);

        currentBiomeCount = count;
        initialized = true;

        LOGGER.info("[GPU Loader] BiomeGPUBuffer uploaded: {} biomes ({} bytes)",
                count, totalFloats * Float.BYTES);
    }

    /**
     * バイオーム出力バッファを指定サイズで確保/再確保する。
     *
     * @param numPoints 出力するバイオームIDの数（= totalPoints in shader）
     */
    public static void ensureOutputBuffer(int numPoints) {
        if (biomeOutputBuffer == -1) {
            biomeOutputBuffer = GL15.glGenBuffers();
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, biomeOutputBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) numPoints * Integer.BYTES, GL15.GL_STREAM_READ);
    }

    /**
     * SSBOをバインドする。シェーダーDispatch前に呼ぶ。
     */
    public static void bind() {
        if (biomeDataBuffer != -1) {
            GL32.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, biomeDataBuffer);
        }
        if (biomeOutputBuffer != -1) {
            GL32.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, biomeOutputBuffer);
        }
    }

    /**
     * バイオーム出力バッファからIDを読み出す（同期読み出し用）。
     */
    public static void readBiomeIds(IntBuffer dest, int numPoints) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, biomeOutputBuffer);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, dest);
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static int getBiomeCount() {
        return currentBiomeCount;
    }

    public static void cleanup() {
        if (biomeDataBuffer != -1) {
            GL15.glDeleteBuffers(biomeDataBuffer);
            biomeDataBuffer = -1;
        }
        if (biomeOutputBuffer != -1) {
            GL15.glDeleteBuffers(biomeOutputBuffer);
            biomeOutputBuffer = -1;
        }
        currentBiomeCount = 0;
        initialized = false;
        LOGGER.info("[GPU Loader] BiomeGPUBuffer cleaned up.");
    }
}
