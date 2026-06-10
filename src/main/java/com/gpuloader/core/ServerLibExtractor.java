package com.gpuloader.core;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ServerLibExtractor {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static final String[] SERVER_LIBS = {
        "lwjgl-3.3.1.jar",
        "lwjgl-glfw-3.3.1.jar",
        "lwjgl-opengl-3.3.1.jar"
    };

    /**
     * Extracts missing server libraries from the mod JAR into the mods folder.
     * @return true if any library was extracted (meaning the server needs a restart).
     */
    public static boolean extractLibs() {
        boolean extractedAny = false;
        Path modsDir = FMLPaths.MODSDIR.get();

        for (String libName : SERVER_LIBS) {
            Path targetFile = modsDir.resolve(libName);
            
            if (!Files.exists(targetFile)) {
                String resourcePath = "/server-libs/" + libName + ".disabled";
                try (InputStream in = ServerLibExtractor.class.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        LOGGER.warn("[GPU Loader] Server library not found in JAR: {}", resourcePath);
                        continue;
                    }
                    Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    injectModsToml(targetFile, libName);
                    LOGGER.info("[GPU Loader] Extracted and patched server library: {}", libName);
                    extractedAny = true;
                } catch (Exception e) {
                    LOGGER.error("[GPU Loader] Failed to extract server library: {}", libName, e);
                }
            }
        }
        
        return extractedAny;
    }

    private static void injectModsToml(Path targetJar, String libName) throws Exception {
        String modId = libName.replace(".jar", "").replace("-", "_").replace(".", "_");
        java.net.URI uri = java.net.URI.create("jar:" + targetJar.toUri().toString());
        try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of("create", "false"))) {
            Path manifestDir = fs.getPath("META-INF");
            if (!Files.exists(manifestDir)) Files.createDirectories(manifestDir);
            Path modsToml = manifestDir.resolve("mods.toml");
            String tomlContent = "modLoader=\"lowcodefml\"\n" +
                                 "loaderVersion=\"[47,)\"\n" +
                                 "license=\"MIT\"\n" +
                                 "[[mods]]\n" +
                                 "modId=\"" + modId + "\"\n" +
                                 "version=\"3.3.1\"\n" +
                                 "displayName=\"" + libName + "\"\n";
            Files.writeString(modsToml, tomlContent, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}
