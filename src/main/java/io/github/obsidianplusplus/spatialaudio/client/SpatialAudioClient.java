package io.github.obsidianplusplus.spatialaudio.client;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.acoustics.AcousticsManager;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRenderer;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.audio.AudioUpdateThread;
import io.github.obsidianplusplus.spatialaudio.client.compat.ModCompat;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.config.SpatialRenderer;
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
			SpatialAudio.LOGGER.info("检测到 Simple Voice Chat：语音使用其独立的 OpenAL 设备与上下文播放，不经过本模组处理，二者互不干扰");
		}

		KeyBindings.register();
		SpatialAudioHud.register();
		SpatialAudioCommands.register();

		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			KeyBindings.handle(minecraft);
			AcousticsManager.get().tick(minecraft);
			AppleRenderer.get().tick();
			showSetupHintIfNeeded(minecraft);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
			HeadTrackingManager.get().stop();
			AppleRenderer.get().shutdown();
			AudioUpdateThread.shutdown();
		});

		AudioUpdateThread.startIfNeeded();
		// 先建立 Apple 音频桥，助手启动参数中需要它的端口与令牌。
		AppleRenderer.get().configure(config);
		HeadTrackingManager.get().start(config);
		SpatialAudio.LOGGER.info("SpatialAudio 已加载：渲染方式 {}，HRTF 模式 {}，头部追踪 {}，声学模拟 {}",
			config.renderer, config.hrtfMode, config.trackingSource, config.acousticsEnabled ? "开启" : "关闭");
	}

	/**
	 * 配置发生变化后，按需重建 Apple 音频桥、重启头部追踪（与助手）或重新加载声音引擎。
	 *
	 * @param before 修改前的配置快照
	 * @param after 修改后的配置
	 */
	public static void onConfigChanged(final SpatialAudioConfig before, final SpatialAudioConfig after) {
		final boolean rendererChanged = before.enabled != after.enabled || before.renderer != after.renderer;
		if (rendererChanged || before.appleBedFormat != after.appleBedFormat) {
			// 用户重新选择了渲染方式或声场格式：清除上一次的回退状态，再次尝试 Apple 渲染。
			AppleRenderer.get().resetFallback();
		}
		AppleRenderer.get().configure(after);
		if (HeadTrackingManager.needsRestart(before, after)) {
			HeadTrackingManager.get().start(after);
		} else {
			HeadTrackingManager.get().applySettings(after);
		}
		// HRTF、渲染方式与声场格式只能在创建 OpenAL 上下文时指定，需要重新加载声音引擎（与切换原版“方向性音频”相同）。
		final boolean hrtfChanged = before.hrtfMode != after.hrtfMode || !Objects.equals(before.hrtfName, after.hrtfName);
		final boolean builtInActive = AudioSystem.get().activeRenderer() == SpatialRenderer.BUILT_IN;
		if (rendererChanged || AppleRenderer.needsReload(before, after) || hrtfChanged && builtInActive) {
			AudioSystem.get().reloadSoundEngine();
		}
	}

	/**
	 * 重启头部追踪与 macOS 助手（命令 {@code /spatialaudio restart} 与设置界面的重启按钮共用）。
	 * 选择了 Apple 空间音频时，先清除回退状态、确保音频桥存在，再重新加载声音引擎重新打开回环设备，从而再次尝试 Apple 渲染。
	 *
	 * @return 是否同时重启了 Apple 空间音频
	 */
	public static boolean restart() {
		final SpatialAudioConfig config = ConfigManager.get();
		final boolean apple = config.enabled && config.renderer == SpatialRenderer.APPLE && AppleRenderer.platformSupported();
		// 必须在启动助手之前清除回退状态并建立音频桥，助手的启动参数才会带上音频桥端口与令牌。
		AppleRenderer.get().resetFallback();
		AppleRenderer.get().configure(config);
		HeadTrackingManager.get().start(config);
		if (apple) {
			// 回环设备只能在创建 OpenAL 上下文时打开，需要重新加载声音引擎。
			AudioSystem.get().reloadSoundEngine();
		}
		return apple;
	}

	/**
	 * 进入世界后，若检测到 AirPods 且尚未关闭提示，则按当前渲染方式在聊天栏给出 macOS 设置建议：
	 * 内置渲染与 Apple 空间音频都需要关闭系统对游戏（或助手）的空间音频，避免重复空间化；
	 * Apple 系统空间化立体声则相反，需要用户打开它。
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
		final SpatialRenderer renderer = audio.activeRenderer();
		if (mac && audio.isAppleHeadphones()) {
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("spatialaudio.hint.airpods_detected")
				.withStyle(ChatFormatting.AQUA));
			final String key = switch (renderer) {
				case BUILT_IN -> "spatialaudio.hint.disable_system_spatial";
				case APPLE -> "spatialaudio.hint.apple_renderer";
				case APPLE_SYSTEM_STEREO -> "spatialaudio.hint.enable_system_spatial";
			};
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY));
		} else if (renderer == SpatialRenderer.APPLE_SYSTEM_STEREO) {
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("spatialaudio.hint.prefix")
				.append(Component.translatable("spatialaudio.hint.enable_system_spatial")).withStyle(ChatFormatting.GRAY));
		}
		final Component fallback = AppleRenderer.get().fallbackReason();
		if (fallback != null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("spatialaudio.hint.apple_fallback", fallback)
				.withStyle(ChatFormatting.YELLOW));
		}
		if (!audio.hrtf().enabled()) {
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.translatable("spatialaudio.hint.hrtf_off")
				.withStyle(ChatFormatting.YELLOW));
		}
	}
}
