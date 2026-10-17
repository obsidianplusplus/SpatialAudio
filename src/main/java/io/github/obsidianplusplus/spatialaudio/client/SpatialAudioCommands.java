package io.github.obsidianplusplus.spatialaudio.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRenderer;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRendererStatus;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.client.screen.SpatialAudioConfigScreen;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.config.SpatialRenderer;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 客户端命令 {@code /spatialaudio}：
 * <ul>
 *   <li>{@code status} —— 显示当前状态；</li>
 *   <li>{@code recenter} —— 头部回中；</li>
 *   <li>{@code reload} —— 从磁盘重新读取配置；</li>
 *   <li>{@code restart} —— 重启头部追踪与 macOS 助手；选择了 Apple 空间音频时还会清除回退状态并重新加载声音引擎；</li>
 *   <li>{@code renderer [built_in|apple|apple_system_stereo]} —— 查看或切换空间音频渲染方式；</li>
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
				final boolean apple = SpatialAudioClient.restart();
				ctx.getSource().sendFeedback(Component.translatable(apple
					? "spatialaudio.message.restarted_apple" : "spatialaudio.message.restarted"));
				return 1;
			}))
			.then(rendererCommand())
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
	 * 构建 {@code renderer} 子命令：不带参数时显示当前渲染方式，带参数时切换。
	 *
	 * @return 命令节点
	 */
	private static LiteralArgumentBuilder<FabricClientCommandSource> rendererCommand() {
		final LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommands.literal("renderer")
			.executes(ctx -> {
				final SpatialAudioConfig config = ConfigManager.get();
				ctx.getSource().sendFeedback(Component.translatable("spatialaudio.message.renderer_current",
					Component.translatable(config.renderer.translationKey()),
					Component.translatable(AppleRenderer.get().status(config).translationKey())));
				return 1;
			});
		for (final SpatialRenderer renderer : SpatialRenderer.values()) {
			node.then(ClientCommands.literal(renderer.name().toLowerCase(Locale.ROOT))
				.executes(ctx -> setRenderer(ctx.getSource(), renderer)));
		}
		return node;
	}

	/**
	 * 切换渲染方式（与设置界面相同：保存配置，并按需重启助手、重新加载声音引擎）。
	 *
	 * @param source 命令来源
	 * @param renderer 新的渲染方式
	 * @return 命令结果
	 */
	private static int setRenderer(final FabricClientCommandSource source, final SpatialRenderer renderer) {
		final SpatialAudioConfig before = ConfigManager.snapshot();
		final SpatialAudioConfig after = ConfigManager.snapshot();
		after.renderer = renderer;
		ConfigManager.replace(after);
		SpatialAudioClient.onConfigChanged(before, after);
		source.sendFeedback(Component.translatable("spatialaudio.message.renderer_set", Component.translatable(renderer.translationKey())));
		final AppleRendererStatus status = AppleRenderer.get().status(after);
		if (status.needsAttention()) {
			// 例如系统空间化立体声需要用户在 macOS 中手动打开，或当前系统不支持 Apple 空间音频。
			final MutableComponent hint = Component.translatable(status.hintKey());
			final Component reason = AppleRenderer.get().fallbackReason();
			if (status == AppleRendererStatus.FALLBACK && reason != null) {
				hint.append(" (").append(reason).append(")");
			}
			source.sendFeedback(hint.withStyle(ChatFormatting.YELLOW));
		}
		return 1;
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
