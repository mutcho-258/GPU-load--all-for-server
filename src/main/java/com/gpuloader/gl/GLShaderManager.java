package com.gpuloader.gl;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL43;
import org.lwjgl.BufferUtils;
import net.minecraftforge.fml.loading.FMLPaths;

public class GLShaderManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Integer> PROGRAMS = new HashMap<>();
    private static boolean supportsCompute = false;
    private static String gpuRenderer = "Unknown";
    private static String gpuVendor = "Unknown";
    private static boolean isIntegratedGPU = false;

    private static int optimalLocalSize = 256;
    private static Path cacheDir;
    private static String gpuInfoHash = "";

    public static void init() {
        int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION);
        int minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);

        // Detect GPU
        gpuRenderer = GL11.glGetString(GL11.GL_RENDERER);
        gpuVendor = GL11.glGetString(GL11.GL_VENDOR);
        if (gpuRenderer == null)
            gpuRenderer = "Unknown";
        if (gpuVendor == null)
            gpuVendor = "Unknown";

        String rendererLower = gpuRenderer.toLowerCase();
        String vendorLower = gpuVendor.toLowerCase();

        isIntegratedGPU = rendererLower.contains("intel")
                || rendererLower.contains("llvmpipe")
                || rendererLower.contains("swrast")
                || rendererLower.contains("mesa")
                || (rendererLower.contains("amd") && rendererLower.contains("vega")
                        && !rendererLower.contains("rx vega"));

        // Determine optimal local size
        if (vendorLower.contains("nvidia") || rendererLower.contains("nvidia")) {
            optimalLocalSize = 32; // NVIDIA Warp size
        } else if (vendorLower.contains("amd") || rendererLower.contains("radeon")) {
            optimalLocalSize = 64; // AMD Wavefront size
        } else {
            optimalLocalSize = 256; // Fallback
        }

        LOGGER.info("=== GPU Loader: GPU Detection ===");
        LOGGER.info("  Renderer: {}", gpuRenderer);
        LOGGER.info("  Vendor:   {}", gpuVendor);
        LOGGER.info("  OpenGL:   {}.{}", major, minor);
        LOGGER.info("  Optimal Workgroup Size: {}", optimalLocalSize);

        // Prepare cache directory
        try {
            cacheDir = FMLPaths.CONFIGDIR.get().resolve("gpuloader/shaders");
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
            }

            // Create a hash of the GPU info to invalidate cache if driver/hardware changes
            String glVersion = GL11.glGetString(GL11.GL_VERSION);
            String fullGpuInfo = gpuVendor + "|" + gpuRenderer + "|" + (glVersion != null ? glVersion : "");
            gpuInfoHash = bytesToHex(
                    MessageDigest.getInstance("SHA-256").digest(fullGpuInfo.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 8);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize shader cache directory", e);
        }

        if (isIntegratedGPU) {
            LOGGER.warn("========================================");
            LOGGER.warn("  WARNING: Integrated GPU detected!");
            LOGGER.warn("  GPU: {}", gpuRenderer);
            LOGGER.warn("  For best performance, switch to your");
            LOGGER.warn("  dedicated GPU (NVIDIA/AMD).");
            LOGGER.warn("  NVIDIA: NVIDIA Control Panel > Manage 3D Settings");
            LOGGER.warn("    > Program Settings > javaw.exe > High-performance NVIDIA processor");
            LOGGER.warn("  Windows: Settings > Display > Graphics");
            LOGGER.warn("    > Add javaw.exe > Options > High performance");
            LOGGER.warn("========================================");
        } else {
            LOGGER.info("  Dedicated GPU detected. Good!");
        }

        supportsCompute = (major > 4 || (major == 4 && minor >= 3));

        if (supportsCompute) {
            LOGGER.info("OpenGL 4.3+ detected. Compute Shaders are supported.");
        } else {
            LOGGER.warn("OpenGL 4.3 compute shaders NOT supported (Detected version: {}.{}).", major, minor);
        }
    }

    public static int getOptimalLocalSize() {
        return optimalLocalSize;
    }

    public static String getGpuRenderer() {
        return gpuRenderer;
    }

    public static String getGpuVendor() {
        return gpuVendor;
    }

    public static boolean isIntegratedGPU() {
        return isIntegratedGPU;
    }

    public static boolean isSupported() {
        return supportsCompute;
    }

    public static int createComputeProgram(String name, String source) {
        if (!supportsCompute)
            return -1;

        String sourceHash = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            sourceHash = bytesToHex(hash).substring(0, 16);
        } catch (Exception e) {
            LOGGER.warn("Failed to generate shader hash", e);
        }

        String cacheFileName = name + "_" + gpuInfoHash + "_" + sourceHash + ".bin";
        Path cacheFile = cacheDir != null ? cacheDir.resolve(cacheFileName) : null;

        // Try loading from cache
        if (cacheFile != null && Files.exists(cacheFile)) {
            int program = tryLoadFromCache(name, cacheFile);
            if (program != -1) {
                LOGGER.info("[GPU] Loaded cached shader program: {}", name);
                PROGRAMS.put(name, program);
                return program;
            }
        }

        // Normal compile
        long start = System.currentTimeMillis();
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Failed to compile compute shader [{}]: {}", name, GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return -1;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);

        // Hint for program binary retrieval
        GL41.glProgramParameteri(program, GL41.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL11.GL_TRUE);

        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Failed to link compute program [{}]: {}", name, GL20.glGetProgramInfoLog(program));
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(shader);
            return -1;
        }

        GL20.glDeleteShader(shader);
        LOGGER.info("[GPU] Compiled and linked shader program: {} ({} ms)", name, (System.currentTimeMillis() - start));

        // Save to cache
        if (cacheFile != null) {
            saveToCache(program, cacheFile);
        }

        PROGRAMS.put(name, program);
        return program;
    }

    private static int tryLoadFromCache(String name, Path file) {
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length < 8)
                return -1;

            ByteBuffer buf = BufferUtils.createByteBuffer(data.length - 4);
            int binaryFormat = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8)
                    | (data[3] & 0xFF);
            buf.put(data, 4, data.length - 4).flip();

            int program = GL20.glCreateProgram();
            GL41.glProgramBinary(program, binaryFormat, buf);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(program);
                return -1;
            }
            return program;
        } catch (Exception e) {
            LOGGER.warn("Failed to load shader cache for {}: {}", name, e.getMessage());
            return -1;
        }
    }

    private static void saveToCache(int program, Path file) {
        try {
            int length = GL20.glGetProgrami(program, GL41.GL_PROGRAM_BINARY_LENGTH);
            if (length <= 0)
                return;

            int[] binaryFormat = new int[1];
            ByteBuffer binary = BufferUtils.createByteBuffer(length);
            GL41.glGetProgramBinary(program, null, binaryFormat, binary);

            try (OutputStream out = Files.newOutputStream(file)) {
                out.write((binaryFormat[0] >> 24) & 0xFF);
                out.write((binaryFormat[0] >> 16) & 0xFF);
                out.write((binaryFormat[0] >> 8) & 0xFF);
                out.write(binaryFormat[0] & 0xFF);

                byte[] bytes = new byte[length];
                binary.get(bytes);
                out.write(bytes);
            }
            LOGGER.debug("[GPU] Saved shader binary cache to {}", file.getFileName());
        } catch (Exception e) {
            LOGGER.warn("Failed to save shader cache: {}", e.getMessage());
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String loadShaderSource(String path, boolean injectLocalSize) {
        try (InputStream in = GLShaderManager.class.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.error("Shader resource not found: {}", path);
                return null;
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (injectLocalSize) {
                source = source.replaceFirst("#version 430 core",
                        "#version 430 core\n#define LOCAL_SIZE " + optimalLocalSize);
            }
            return source;
        } catch (Exception e) {
            LOGGER.error("Failed to load shader source: {}", path, e);
            return null;
        }
    }

    public static String loadShaderSource(String path) {
        return loadShaderSource(path, false);
    }

    public static void useProgram(String name) {
        Integer program = PROGRAMS.get(name);
        if (program != null) {
            GL20.glUseProgram(program);
        }
    }

    public static void cleanup() {
        for (int program : PROGRAMS.values()) {
            GL20.glDeleteProgram(program);
        }
        PROGRAMS.clear();
    }
}
