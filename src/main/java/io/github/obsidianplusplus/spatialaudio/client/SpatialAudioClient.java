package io.github.obsidianplusplus.spatialaudio.client;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.acoustics.AcousticsManager;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.audio.AudioUpdateThread;
import io.github.obsidianplusplus.spatialaudio.client.compat.ModCompat;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import io.github.obsidianplusplus.spatialaudio.headtracking.MacHelperLauncher;
import java.util.Objects;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 模组客户端入口：加载配置、注册按键/叠加层/命令，并启动头部追踪与音频更新线程。
 */
public final class SpatialAudioClient implements ClientModInitializer {
	/** 本次会话是否已显示过设置提示。 */
	private static boolean hintShown;

	@Override
	public void onInitializeClient() {
		ConfigManager.load();
		final SpatialAudioConfig config = ConfigManager.get();
		AudioSystem.get().setSoundPhysicsPresent(ModCompat.isSoundPhysicsLoaded());
		if (ModCompat.isSoundPhysicsLoaded()) {
			SpatialAudio.LOGGER.info("检测到 Sound Physics Remastered：{}", config.respectSoundPhysicsMod
				? "本模组的声学模拟将自动让位，仅保留 HRTF 与头部追踪" : "已按配置强制同时启用本模组声学模拟");
		}

		if (ModCompat.isVoiceChatLoaded()) {
			SpatialAudio.LOGGER.info("检测到 Simple Voice Chat：使用同一 OpenAL 上下文的语音将自动获得 HRTF 与头部追踪");
		}

		KeyBindings.register();
		SpatialAudioHud.register();
		SpatialAudioCommands.register();

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			KeyBindings.handle(minecraft);
			AcousticsManager.get().tick(minecraft);
			showSetupHintIfNeeded(minecraft);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
			HeadTrackingManager.get().stop();
			AudioUpdateThread.shutdown();
		});

		AudioUpdateThread.startIfNeeded();
		HeadTrackingManager.get().start(config);
		SpatialAudio.LOGGER.info("SpatialAudio 已加载：HRTF 模式 {}，头部追踪 {}，声学模拟 {}",
			config.hrtfMode, config.trackingSource, config.acousticsEnabled ? "开启" : "关闭");
	}

	/**
	 * 配置发生变化后，按需重启头部追踪或重新加载声音引擎。
	 *
	 * @param before 修改前的配置快照
	 * @param after 修改后的配置
	 */
	public static void onConfigChanged(final SpatialAudioConfig before, final SpatialAudioConfig after) {
		if (HeadTrackingManager.needsRestart(before, after)) {
			HeadTrackingManager.get().start(after);
		} else {
			HeadTrackingManager.get().applySettings(after);
		}
		// HRTF 相关属性只能在创建 OpenAL 上下文时指定，需要重新加载声音引擎（与切换原版“方向性音频”相同）。
		if (before.enabled != after.enabled || before.hrtfMode != after.hrtfMode || !Objects.equals(before.hrtfName, after.hrtfName)) {
			AudioSystem.get().reloadSoundEngine();
		}
	}

	/**
	 * 进入世界后，若检测到 AirPods 且尚未关闭提示，则在聊天栏给出 macOS 设置建议。
	 *
	 * @param minecraft 客户端实例
	 */
	private static void showSetupHintIfNeeded(final Minecraft minecraft) {
		if (hintShown || minecraft.player == null || !ConfigManager.get().showSetupHints) {
			return;
		}
		hintShown = true;
		final AudioSystem audio = AudioSystem.get();
		final boolean mac = MacHelperLauncher.isMacOs();
		if (mac && audio.isAppleHeadphones()) {
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("spatialaudio.hint.airpods_detected")
				.withStyle(ChatFormatting.AQUA));
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("spatialaudio.hint.disable_system_spatial")
				.withStyle(ChatFormatting.GRAY));
		}
		if (!audio.hrtf().enabled()) {
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("spatialaudio.hint.hrtf_off")
				.withStyle(ChatFormatting.YELLOW));
		}
	}
}
