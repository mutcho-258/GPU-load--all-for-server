package com.gpuloader.gl;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Mod JAR内に埋め込まれたLWJGLネイティブライブラリ(.dll/.so)を
 * ローカルディレクトリに展開し、org.lwjgl.librarypath を設定する。
 * 
 * これにより専用サーバーでもGPU機能が利用可能になる。
 * LWJGLクラスの静的初期化が行われる前に呼び出す必要がある。
 */
public class NativeExtractor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean extracted = false;

    // Mod JAR内に埋め込まれたネイティブファイル名リスト
    private static final String[] WINDOWS_NATIVES = {
        "lwjgl.dll",
        "lwjgl_opengl.dll",
        "glfw.dll"
    };

    private static final String[] LINUX_NATIVES = {
        "liblwjgl.so",
        "liblwjgl_opengl.so",
        "libglfw.so"
    };

    /**
     * ネイティブライブラリをMod JARから展開し、LWJGLのライブラリパスを設定する。
     * LWJGLの静的初期化前に呼び出すこと。
     *
     * @return true = ネイティブの展開・設定に成功, false = 失敗
     */
    public static boolean extractAndConfigure() {
        if (extracted) return true;

        // 既にライブラリパスが設定されている場合はスキップ
        String existingPath = System.getProperty("org.lwjgl.librarypath");
        if (existingPath != null && !existingPath.isEmpty()) {
            LOGGER.info("[GPU Loader] org.lwjgl.librarypath already set: {}", existingPath);
            extracted = true;
            return true;
        }

        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isWindows = osName.contains("win");
        boolean isLinux = osName.contains("linux");

        String[] nativeFiles;
        if (isWindows) {
            nativeFiles = WINDOWS_NATIVES;
        } else if (isLinux) {
            nativeFiles = LINUX_NATIVES;
        } else {
            LOGGER.warn("[GPU Loader] Unsupported OS for native extraction: {}", osName);
            return false;
        }

        // 展開先ディレクトリ: config/gpuloader/natives/
        Path extractDir;
        try {
            extractDir = FMLPaths.CONFIGDIR.get().resolve("gpuloader").resolve("natives");
            Files.createDirectories(extractDir);
        } catch (IOException e) {
            LOGGER.error("[GPU Loader] Failed to create native extraction directory", e);
            return false;
        }

        boolean allExtracted = true;
        for (String nativeFile : nativeFiles) {
            Path targetFile = extractDir.resolve(nativeFile);
            
            // JAR内リソースパス: /natives/<filename>.bundled
            String resourcePath = "/natives/" + nativeFile + ".bundled";
            
            try (InputStream in = NativeExtractor.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.warn("[GPU Loader] Native resource not found in JAR: {}", resourcePath);
                    allExtracted = false;
                    continue;
                }

                // ファイルが既に存在し、サイズが同じなら再展開をスキップ
                if (Files.exists(targetFile)) {
                    long existingSize = Files.size(targetFile);
                    byte[] data = in.readAllBytes();
                    if (existingSize == data.length) {
                        LOGGER.debug("[GPU Loader] Native already extracted: {}", nativeFile);
                        continue;
                    }
                    // サイズが異なる場合は上書き
                    Files.write(targetFile, data);
                } else {
                    Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                
                LOGGER.info("[GPU Loader] Extracted native: {} -> {}", nativeFile, targetFile);
            } catch (IOException e) {
                LOGGER.error("[GPU Loader] Failed to extract native: {}", nativeFile, e);
                allExtracted = false;
            }
        }

        if (!allExtracted) {
            LOGGER.warn("[GPU Loader] Some native libraries could not be extracted.");
            return false;
        }

        // LWJGLにネイティブライブラリの場所を教える
        String nativePath = extractDir.toAbsolutePath().toString();
        System.setProperty("org.lwjgl.librarypath", nativePath);
        LOGGER.info("[GPU Loader] Set org.lwjgl.librarypath = {}", nativePath);

        extracted = true;
        return true;
    }
}
