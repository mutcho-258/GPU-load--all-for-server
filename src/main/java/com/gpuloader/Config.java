package com.gpuloader;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@Mod.EventBusSubscriber(modid = GPUMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();

    // Client Settings (gpuloader-client.toml)
    public static final ForgeConfigSpec.BooleanValue ENABLE_ALL_FEATURES;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CHUNK_MESH;
    public static final ForgeConfigSpec.BooleanValue ENABLE_ENTITY_MDI;
    public static final ForgeConfigSpec.BooleanValue ENABLE_PARTICLES;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CULLING;

    // Common Settings (gpuloader-server.toml)
    public static final ForgeConfigSpec.BooleanValue ENABLE_TERRAIN_NOISE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_TERRAIN_NOISE;
    public static final ForgeConfigSpec.IntValue TERRAIN_NOISE_BATCH_SIZE;
    public static final ForgeConfigSpec.BooleanValue USE_OPENCL_FOR_NOISE;
    public static final ForgeConfigSpec.DoubleValue COMPUTE_BATCH_GATHER_MS;
    public static final ForgeConfigSpec.BooleanValue USE_SHARED_CONTEXT;
    public static final ForgeConfigSpec.BooleanValue ENABLE_GPU_AI;
    public static final ForgeConfigSpec.IntValue AI_UPDATE_BATCH_SIZE;
    public static final ForgeConfigSpec.IntValue AI_COMPUTE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue AI_POI_RANGE;
    public static final ForgeConfigSpec.DoubleValue AI_DANGER_RANGE;
    public static final ForgeConfigSpec.DoubleValue AI_GPU_BLEND_FACTOR;

    static {
        // ---- Client Side Settings ----
        CLIENT_BUILDER.push("General Settings");
        ENABLE_ALL_FEATURES = CLIENT_BUILDER
                .comment("Master toggle for all GPU acceleration features on the client.")
                .define("enableAllFeatures", true);
        CLIENT_BUILDER.pop();

        CLIENT_BUILDER.push("Rendering Features");
        ENABLE_CHUNK_MESH = CLIENT_BUILDER
                .comment("Enable GPU-accelerated chunk mesh generation.")
                .define("enableChunkMesh", true);

        ENABLE_ENTITY_MDI = CLIENT_BUILDER
                .comment("Enable Multi-Draw Indirect for entity rendering.")
                .define("enableEntityMDI", true);

        ENABLE_PARTICLES = CLIENT_BUILDER
                .comment("Enable GPU-accelerated particle simulation.")
                .define("enableParticles", true);

        ENABLE_CULLING = CLIENT_BUILDER
                .comment("Enable GPU-driven occlusion culling.")
                .define("enableCulling", true);
        CLIENT_BUILDER.pop();

        // ---- Server Side Settings ----
        SERVER_BUILDER.push("Terrain Generation Settings");
        ENABLE_TERRAIN_NOISE = SERVER_BUILDER
                .comment("Enable GPU-accelerated terrain noise calculation. (Server-side logic)")
                .define("enableTerrainNoise", true);

        DEBUG_TERRAIN_NOISE = SERVER_BUILDER
                .comment("Enable debug logging for GPU terrain noise generation (e.g. per-batch logs and CPU fallback warnings).")
                .define("debugTerrainNoise", false);

        TERRAIN_NOISE_BATCH_SIZE = SERVER_BUILDER
                .comment("Number of chunks to batch for GPU noise calculation. Range: 2-32")
                .defineInRange("terrainNoiseBatchSize", 8, 2, 32);

        USE_OPENCL_FOR_NOISE = SERVER_BUILDER
                .comment("Use OpenCL instead of OpenGL Compute Shaders for terrain noise.")
                .define("useOpenCLForNoise", true);

        COMPUTE_BATCH_GATHER_MS = SERVER_BUILDER
                .comment("Time in milliseconds to wait and gather chunk requests from multiple threads. (0.1 - 10.0)")
                .defineInRange("computeBatchGatherMs", 0.5, 0.1, 10.0);

        USE_SHARED_CONTEXT = SERVER_BUILDER
                .comment("Use a shared OpenGL context on a background thread to bypass render thread latency.")
                .define("useSharedContext", true);
        SERVER_BUILDER.pop();

        // ---- Mob AI Settings (top-level, not nested) ----
        SERVER_BUILDER.push("Mob AI Settings");
        ENABLE_GPU_AI = SERVER_BUILDER
                .comment("Enable GPU-accelerated Mob AI (Utility AI).")
                .define("enableGpuAi", true);

        AI_UPDATE_BATCH_SIZE = SERVER_BUILDER
                .comment("Number of mobs to update context (POI/Danger) per tick. Higher = more CPU but more responsive. Default: 20")
                .defineInRange("aiUpdateBatchSize", 20, 1, 1000);

        AI_COMPUTE_INTERVAL_TICKS = SERVER_BUILDER
                .comment("Interval in ticks to run GPU AI computation. (1-5)")
                .defineInRange("aiComputeIntervalTicks", 2, 1, 5);

        AI_POI_RANGE = SERVER_BUILDER
                .comment("Range in blocks for mobs to search for POIs/Players. (4.0 - 128.0)")
                .defineInRange("aiPoiRange", 16.0, 4.0, 128.0);

        AI_DANGER_RANGE = SERVER_BUILDER
                .comment("Range in blocks for mobs to detect danger sources. (4.0 - 128.0)")
                .defineInRange("aiDangerRange", 12.0, 4.0, 128.0);

        AI_GPU_BLEND_FACTOR = SERVER_BUILDER
                .comment("How much GPU influences mob movement. 0.0=pure vanilla, 1.0=full GPU. (0.0-0.5)")
                .defineInRange("aiGpuBlendFactor", 0.25, 0.0, 0.5);
        SERVER_BUILDER.pop();
    }

    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();
    public static final ForgeConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    // Cache variables for easy access (updated on load/reload)
    public static boolean enableAll;
    public static boolean chunkMesh;
    public static boolean entityMDI;
    public static boolean particles;
    public static boolean culling;
    public static boolean terrainNoise = true;
    public static boolean debugTerrainNoise = false;
    public static int terrainNoiseBatch = 8;
    public static boolean useOpenCL = true;
    public static double computeBatchGatherMs = 0.5;
    public static boolean useSharedContext = true;
    public static boolean gpuAi = true;
    public static int aiUpdateBatch = 20;
    public static int aiComputeInterval = 2;
    public static double aiPoiRange = 16.0;
    public static double aiDangerRange = 12.0;
    public static double gpuBlendFactor = 0.25;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == CLIENT_SPEC) {
            enableAll = ENABLE_ALL_FEATURES.get();
            chunkMesh = ENABLE_CHUNK_MESH.get();
            entityMDI = ENABLE_ENTITY_MDI.get();
            particles = ENABLE_PARTICLES.get();
            culling = ENABLE_CULLING.get();
        } else if (event.getConfig().getSpec() == SERVER_SPEC) {
            terrainNoise = ENABLE_TERRAIN_NOISE.get();
            debugTerrainNoise = DEBUG_TERRAIN_NOISE.get();
            terrainNoiseBatch = TERRAIN_NOISE_BATCH_SIZE.get();
            useOpenCL = USE_OPENCL_FOR_NOISE.get();
            computeBatchGatherMs = COMPUTE_BATCH_GATHER_MS.get();
            useSharedContext = USE_SHARED_CONTEXT.get();
            gpuAi = ENABLE_GPU_AI.get();
            aiUpdateBatch = AI_UPDATE_BATCH_SIZE.get();
            aiComputeInterval = Math.max(1, Math.min(5, AI_COMPUTE_INTERVAL_TICKS.get()));
            aiPoiRange = AI_POI_RANGE.get();
            aiDangerRange = AI_DANGER_RANGE.get();
            gpuBlendFactor = AI_GPU_BLEND_FACTOR.get();
        }
    }
}
