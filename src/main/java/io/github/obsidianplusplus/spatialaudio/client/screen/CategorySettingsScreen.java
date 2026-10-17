package io.github.obsidianplusplus.spatialaudio.client.screen;

import io.github.obsidianplusplus.spatialaudio.config.CategorySettings;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/**
 * 声音类别设置子界面：逐个类别调整三维定位、遮挡、混响发送与屏幕锚定。
 *
 * <p>修改直接写入上级界面的工作副本，由上级界面在关闭时统一应用。
 */
public final class CategorySettingsScreen extends OptionsSubScreen {
	/** 上级界面的工作副本。 */
	private final SpatialAudioConfig working;

	/**
	 * 创建子界面。
	 *
	 * @param parent 上级界面
	 * @param working 配置工作副本
	 */
	public CategorySettingsScreen(final Screen parent, final SpatialAudioConfig working) {
		super(parent, Minecraft.getInstance().options, Component.translatable("spatialaudio.config.categories.title"));
		this.working = working;
	}

	@Override
	protected void addOptions() {
		final String prefix = SpatialAudioConfigScreen.OPTION + "category.";
		for (final SoundSource source : SoundSource.values()) {
			if (source == SoundSource.MASTER) {
				continue;
			}
			final CategorySettings settings = this.working.category(source);
			this.list.addHeader(Component.translatable("soundCategory." + source.getName()));
			this.list.addSmall(
				OptionFactory.toggle(prefix + "spatialize", settings.spatialize, v -> settings.spatialize = v),
				OptionFactory.toggle(prefix + "occlusion", settings.occlusion, v -> settings.occlusion = v),
				OptionFactory.percent(prefix + "reverb_send", settings.reverbSend, 200, v -> settings.reverbSend = v),
				OptionFactory.toggle(prefix + "screen_anchored", settings.screenAnchored, v -> settings.screenAnchored = v)
			);
		}
	}

	@Override
	public void removed() {
		// 由上级界面统一保存，这里不写入原版选项文件。
		if (this.list != null) {
			this.list.applyUnsavedChanges();
		}
	}
}
