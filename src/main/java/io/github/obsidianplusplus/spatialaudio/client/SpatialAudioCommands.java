package io.github.obsidianplusplus.spatialaudio.client;

import com.mojang.brigadier.CommandDispatcher;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.client.screen.SpatialAudioConfigScreen;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 客户端命令 {@code /spatialaudio}：
 * <ul>
 *   <li>{@code status} —— 显示当前状态；</li>
 *   <li>{@code recenter} —— 头部回中；</li>
 *   <li>{@code reload} —— 从磁盘重新读取配置；</li>
 *   <li>{@code restart} —— 重启头部追踪与 AirPods 助手；</li>
 *   <li>{@code hud} —— 开关调试叠加层；</li>
 *   <li>{@code hrtf} —— 列出可用的 HRTF 数据集；</li>
 *   <li>{@code config} —— 打开设置界面。</li>
 * </ul>
 */
public final class SpatialAudioCommands {
	private SpatialAudioCommands() {
	}

	/**
	 * 注册命令。
	 */
	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerTo(dispatcher));
	}

	/**
	 * 向调度器注册命令树。
	 *
	 * @param dispatcher 命令调度器
	 */
	private static void registerTo(final CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("spatialaudio")
			.executes(ctx -> status(ctx.getSource()))
			.then(ClientCommands.literal("status").executes(ctx -> status(ctx.getSource())))
			.then(ClientCommands.literal("recenter").executes(ctx -> {
				HeadTrackingManager.get().recenter();
				ctx.getSource().sendFeedback(Component.translatable("spatialaudio.message.recentered"));
				return 1;
			}))
			.then(ClientCommands.literal("reload").executes(ctx -> {
				final SpatialAudioConfig before = ConfigManager.snapshot();
				ConfigManager.load();
				SpatialAudioClient.onConfigChanged(before, ConfigManager.get());
				ctx.getSource().sendFeedback(Component.translatable("spatialaudio.message.reloaded"));
				return 1;
			}))
			.then(ClientCommands.literal("restart").executes(ctx -> {
				HeadTrackingManager.get().start(ConfigManager.get());
				ctx.getSource().sendFeedback(Component.translatable("spatialaudio.message.restarted"));
				return 1;
			}))
			.then(ClientCommands.literal("hud").executes(ctx -> {
				final SpatialAudioConfig before = ConfigManager.snapshot();
				final SpatialAudioConfig after = ConfigManager.snapshot();
				after.showHud = !after.showHud;
				ConfigManager.replace(after);
				SpatialAudioClient.onConfigChanged(before, after);
				return 1;
			}))
			.then(ClientCommands.literal("hrtf").executes(ctx -> {
				final List<String> names = AudioSystem.get().availableHrtfs();
				ctx.getSource().sendFeedback(Component.translatable("spatialaudio.message.hrtf_list", names.size()));
				for (final String name : names) {
					ctx.getSource().sendFeedback(Component.literal(" - " + name));
				}
				return names.size();
			}))
			.then(ClientCommands.literal("config").executes(ctx -> {
				final Minecraft minecraft = Minecraft.getInstance();
				// 命令在聊天界面中执行，需等聊天界面关闭后再打开设置界面。
				minecraft.execute(() -> minecraft.gui.setScreen(new SpatialAudioConfigScreen(null)));
				return 1;
			})));
	}

	/**
	 * 输出状态信息。
	 *
	 * @param source 命令来源
	 * @return 命令结果
	 */
	private static int status(final FabricClientCommandSource source) {
		for (final StatusLines.Line line : StatusLines.build(true)) {
			source.sendFeedback(Component.empty().append(line.text())
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(line.color() & 0xFFFFFF))));
		}
		return 1;
	}
}
