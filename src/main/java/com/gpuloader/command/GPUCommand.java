package com.gpuloader.command;

import com.gpuloader.gl.GLShaderManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
public class GPUCommand {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gpustat")
                .executes(context -> {
                    boolean gl = GLShaderManager.isSupported();
                    String renderer = GLShaderManager.getGpuRenderer();
                    String vendor = GLShaderManager.getGpuVendor();
                    boolean integrated = GLShaderManager.isIntegratedGPU();

                    context.getSource().sendSuccess(() -> Component.literal("§6[GPU Loader] §fStatus:"), false);
                    context.getSource().sendSuccess(
                            () -> Component.literal("  §7GPU: §f" + renderer), false);
                    context.getSource().sendSuccess(
                            () -> Component.literal("  §7Vendor: §f" + vendor), false);
                    context.getSource().sendSuccess(
                            () -> Component.literal("  §7Type: " + (integrated ? "§c内蔵GPU (Integrated)" : "§a専用GPU (Dedicated)")), false);
                    context.getSource().sendSuccess(
                            () -> Component.literal("  §7Compute Shaders: " + (gl ? "§aREADY" : "§cNOT SUPPORTED")), false);
                    context.getSource().sendSuccess(
                            () -> Component.literal("  §7Chunk Mesh: " + com.gpuloader.core.ModCompatChecker.getStatusMessage()), false);

                    if (integrated) {
                        context.getSource().sendSuccess(
                                () -> Component.literal(""), false);
                        context.getSource().sendSuccess(
                                () -> Component.literal("§c§l⚠ 内蔵GPUが使用されています！"), false);
                        context.getSource().sendSuccess(
                                () -> Component.literal("§eパフォーマンスを最大化するには、専用GPU(NVIDIA/AMD)に切り替えてください:"), false);
                        context.getSource().sendSuccess(
                                () -> Component.literal("§7  NVIDIA: コントロールパネル > 3D設定の管理 > javaw.exe > 高パフォーマンス"), false);
                        context.getSource().sendSuccess(
                                () -> Component.literal("§7  Windows: 設定 > ディスプレイ > グラフィックス > javaw.exe > 高パフォーマンス"), false);
                    }

                    return 1;
                }));

        dispatcher.register(Commands.literal("gpudebug")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("§6[GPU Loader] §fScanning internal methods... check console!"), false);
                    
                    try {
                        LOGGER.info("=== [GPU Loader] RUNTIME METHOD SCAN START ===");
                        LOGGER.info("Target: net.minecraft.world.level.levelgen.NoiseChunk");
                        for (java.lang.reflect.Method m : net.minecraft.world.level.levelgen.NoiseChunk.class.getDeclaredMethods()) {
                            LOGGER.info("[GPU Loader] Method: {} | Params: {}", m.getName(), m.getParameterCount());
                        }
                        LOGGER.info("=== [GPU Loader] RUNTIME METHOD SCAN END ===");
                    } catch (Exception e) {
                        LOGGER.error("Scan failed", e);
                    }
                    
                    return 1;
                }));
    }
}
