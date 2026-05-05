package com.gpuloader;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(GPUMod.MODID)
public class GPUMod {
    public static final String MODID = "gpuloader";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static boolean initialized = false;
    private static volatile boolean biomeUploadPending = false;

    public GPUMod(FMLJavaModLoadingContext context) {
        // Register API implementation
        com.gpuloader.api.GPULoaderAPI.registerInternal(com.gpuloader.core.GPUComputeManager.getInstance());

        IEventBus modEventBus = context.getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Migrate old config if necessary
        migrateOldConfig();

        // Register our mod's ForgeConfigSpec
        context.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC, "gpuloader/gpuloader-client.toml");
        // SERVER configs are stored per-world in saves/<world>/serverconfig/
        context.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC, "gpuloader-server.toml");
    }

    private void migrateOldConfig() {
        java.nio.file.Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        java.nio.file.Path oldConfig = configDir.resolve("gpuloader-client.toml");
        java.nio.file.Path newDir = configDir.resolve("gpuloader");
        java.nio.file.Path newClientConfig = newDir.resolve("gpuloader-client.toml");
        java.nio.file.Path newServerConfig = newDir.resolve("gpuloader-server.toml");

        if (java.nio.file.Files.exists(oldConfig)) {
            try {
                if (!java.nio.file.Files.exists(newDir)) {
                    java.nio.file.Files.createDirectories(newDir);
                }
                // Copy to both locations so both specs can pull their respective values from the old file
                if (!java.nio.file.Files.exists(newClientConfig)) {
                    java.nio.file.Files.copy(oldConfig, newClientConfig);
                }
                if (!java.nio.file.Files.exists(newServerConfig)) {
                    java.nio.file.Files.copy(oldConfig, newServerConfig);
                }
                // Delete old one
                java.nio.file.Files.delete(oldConfig);
                LOGGER.info("Successfully migrated old config to 'config/gpuloader/' directory.");
            } catch (java.io.IOException e) {
                LOGGER.error("Failed to migrate old config", e);
            }
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("GPU Acceleration Mod: Common Setup");
        // Check for conflicting mods and disable chunk meshing if needed
        com.gpuloader.core.ModCompatChecker.checkAndDisableIfNeeded();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        com.gpuloader.command.GPUCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onLevelLoad(net.minecraftforge.event.level.LevelEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Extract biome parameters on world load (after TerraBlender has injected)
            if (serverLevel.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                boolean success = com.gpuloader.core.BiomeParameterExtractor.extract(serverLevel);
                if (success) {
                    // Schedule SSBO upload on the render thread
                    biomeUploadPending = true;
                }
            }
        }
    }

    @SubscribeEvent
    public void onLevelUnload(net.minecraftforge.event.level.LevelEvent.Unload event) {
        // Clear CPU-side caches (always safe)
        com.gpuloader.core.NoiseBatchManager.clearCache();
        com.gpuloader.core.BiomeParameterExtractor.clear();
        com.gpuloader.core.BiomeResultCache.clear();
        // Only call GPU cleanup if GPU was actually initialized
        if (initialized) {
            com.gpuloader.gl.BiomeGPUBuffer.cleanup();
            com.gpuloader.gl.GPUComputeThread.shutdown();
        }
        biomeUploadPending = false;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isDedicatedServer()) {
            if (!initialized) {
                LOGGER.info("GPU Acceleration Mod: Initializing GPU Infrastructure on Dedicated Server");

                // Step 0: Check and extract server libraries (.jar)
                boolean libsExtracted = com.gpuloader.core.ServerLibExtractor.extractLibs();
                if (libsExtracted) {
                    LOGGER.error("=========================================================");
                    LOGGER.error("[GPU Loader] Missing Server Libraries have been extracted.");
                    LOGGER.error("[GPU Loader] YOU MUST RESTART THE SERVER NOW TO LOAD THEM.");
                    LOGGER.error("=========================================================");
                    System.exit(0);
                    return;
                }

                // Step 1: Extract native libraries (.dll/.so) from mod JAR if needed
                // This MUST happen before any LWJGL class is loaded/initialized
                boolean nativesReady = com.gpuloader.gl.NativeExtractor.extractAndConfigure();
                if (!nativesReady) {
                    LOGGER.warn("GPU Acceleration Mod: Failed to extract native libraries.");
                    LOGGER.warn("GPU features will be DISABLED. The mod will run with CPU-only (vanilla) behavior.");
                    initialized = false;
                    return;
                }

                // Step 2: Initialize GPU infrastructure (GLFW context + OpenGL shaders)
                try {
                    // Start the compute thread which will create the GLFW context and initialize shaders
                    com.gpuloader.gl.GPUComputeThread.init();
                    initialized = true;
                    LOGGER.info("GPU Acceleration Mod: GPU Infrastructure initialized successfully on Dedicated Server!");
                } catch (Throwable e) {
                    // Catch Throwable to handle UnsatisfiedLinkError, NoClassDefFoundError, etc.
                    LOGGER.error("Failed to initialize GPU Infrastructure on server", e);
                    LOGGER.warn("GPU features will be DISABLED. The mod will run with CPU-only (vanilla) behavior.");
                    initialized = false;
                }
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START && net.minecraftforge.fml.loading.FMLEnvironment.dist.isDedicatedServer()) {
            if (initialized && biomeUploadPending) {
                com.gpuloader.gl.GPUComputeThread.submitTask(new com.gpuloader.core.GPUTask<Void>() {
                    @Override
                    public void execute() {
                        com.gpuloader.gl.BiomeGPUBuffer.uploadBiomeData();
                        result.complete(null);
                    }
                });
                biomeUploadPending = false;
            }
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            if (!initialized) {
                LOGGER.info("GPU Acceleration Mod: Initializing GPU Infrastructure on Render Thread");
                try {
                    com.gpuloader.gl.GLShaderManager.init();
                    com.gpuloader.gl.GPUComputeThread.init();
                    initialized = true;
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize GPU Infrastructure", e);
                    initialized = true;
                }
            }

            // Upload biome data if pending (must happen on render/GL thread)
            if (initialized && biomeUploadPending) {
                com.gpuloader.gl.BiomeGPUBuffer.uploadBiomeData();
                biomeUploadPending = false;
            }

            // Process pending GPU tasks on the render thread every tick
            if (initialized) {
                com.gpuloader.core.GPUComputeManager.processRenderThreadTasks();
                if (!Config.useSharedContext) {
                    com.gpuloader.gl.NoiseComputeTask.processReadyBatches();
                }
                com.gpuloader.gl.MeshCullTask.processReadyBatches();
                com.gpuloader.core.MeshCullManager.cleanupExpiredCache();
                com.gpuloader.core.BiomeResultCache.cleanup();
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("GPU Acceleration Mod: Client Setup - Infrastructure initialization deferred to Render Thread");
            com.gpuloader.core.MeshCullManager.init();
        }
    }
}
