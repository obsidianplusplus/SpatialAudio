package io.github.obsidianplusplus.spatialaudio.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.client.screen.SpatialAudioConfigScreen;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.HeadTrackingSource;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 模组按键绑定：回中、开关头部追踪、打开设置界面、开关调试叠加层。
 *
 * <p>默认按键：F6 回中、F7 打开设置；其余默认不绑定，可在“选项 → 控制 → 按键绑定”中修改。
 * Mac 键盘需要同时按住 fn 键才能触发 F 键。
 */
public final class KeyBindings {
	/** 按键分类。 */
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(SpatialAudio.id("main"));

	/** 回中。 */
	private static KeyMapping recenter;

	/** 开关头部追踪。 */
	private static KeyMapping toggleTracking;

	/** 打开设置界面。 */
	private static KeyMapping openConfig;

	/** 开关调试叠加层。 */
	private static KeyMapping toggleHud;

	/** 关闭头部追踪前使用的数据源，用于再次开启时恢复。 */
	private static HeadTrackingSource lastSource = HeadTrackingSource.AUTO;

	private KeyBindings() {
	}

	/**
	 * 注册全部按键。
	 */
	public static void register() {
		recenter = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.spatialaudio.recenter", InputConstants.KEY_F6, CATEGORY));
		toggleTracking = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.spatialaudio.toggle_tracking", InputConstants.UNKNOWN.getValue(), CATEGORY));
		openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.spatialaudio.open_config", InputConstants.KEY_F7, CATEGORY));
		toggleHud = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.spatialaudio.toggle_hud", InputConstants.UNKNOWN.getValue(), CATEGORY));
	}

	/**
	 * 每刻处理按键（主线程）。
	 *
	 * @param minecraft 客户端实例
	 */
	public static void handle(final Minecraft minecraft) {
		while (recenter.consumeClick()) {
			HeadTrackingManager.get().recenter();
			notify(minecraft, Component.translatable("spatialaudio.message.recentered"));
		}
		while (toggleTracking.consumeClick()) {
			final SpatialAudioConfig before = ConfigManager.snapshot();
			final SpatialAudioConfig after = ConfigManager.snapshot();
			if (after.trackingSource == HeadTrackingSource.DISABLED) {
				after.trackingSource = lastSource == HeadTrackingSource.DISABLED ? HeadTrackingSource.AUTO : lastSource;
			} else {
				lastSource = after.trackingSource;
				after.trackingSource = HeadTrackingSource.DISABLED;
			}
			ConfigManager.replace(after);
			SpatialAudioClient.onConfigChanged(before, after);
			notify(minecraft, Component.translatable(after.trackingSource == HeadTrackingSource.DISABLED
				? "spatialaudio.message.tracking_off" : "spatialaudio.message.tracking_on"));
		}
		while (openConfig.consumeClick()) {
			minecraft.gui.setScreen(new SpatialAudioConfigScreen(minecraft.gui.screen()));
		}
		while (toggleHud.consumeClick()) {
			final SpatialAudioConfig before = ConfigManager.snapshot();
			final SpatialAudioConfig after = ConfigManager.snapshot();
			after.showHud = !after.showHud;
			ConfigManager.replace(after);
			SpatialAudioClient.onConfigChanged(before, after);
		}
	}

	/**
	 * 在动作栏显示提示。
	 *
	 * @param minecraft 客户端实例
	 * @param message 提示内容
	 */
	private static void notify(final Minecraft minecraft, final Component message) {
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(message);
		}
	}
}
