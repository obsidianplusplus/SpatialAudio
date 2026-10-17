package io.github.obsidianplusplus.spatialaudio.client.screen;

import com.mojang.blaze3d.Blaze3D;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.client.SpatialAudioClient;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.HeadTrackingSource;
import io.github.obsidianplusplus.spatialaudio.config.HrtfMode;
import io.github.obsidianplusplus.spatialaudio.config.RecenterMode;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import io.github.obsidianplusplus.spatialaudio.headtracking.MacHelperLauncher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * 模组主设置界面。
 *
 * <p>界面编辑的是配置的“工作副本”，关闭界面（或进入子界面）时统一应用：
 * 头部追踪参数即时生效；数据源或端口变化会重启追踪；HRTF 变化会重新加载声音引擎。
 */
public final class SpatialAudioConfigScreen extends OptionsSubScreen {
	/** 选项翻译键前缀。 */
	static final String OPTION = "spatialaudio.option.";

	/** 修改前的配置快照。 */
	private SpatialAudioConfig before;

	/** 正在编辑的工作副本。 */
	private final SpatialAudioConfig working;

	/** 为 true 时关闭界面不再应用工作副本（例如刚刚恢复了默认设置）。 */
	private boolean discardChanges;

	/**
	 * 创建设置界面。
	 *
	 * @param parent 返回时显示的上级界面，可为 null
	 */
	public SpatialAudioConfigScreen(final @Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("spatialaudio.config.title"));
		this.before = ConfigManager.snapshot();
		this.working = ConfigManager.snapshot();
	}

	@Override
	protected void addContents() {
		this.list = this.layout.addToContents(new SpatialOptionsList(this.minecraft, this.width, this));
		this.addOptions();
	}

	@Override
	protected void addOptions() {
		final SpatialOptionsList options = (SpatialOptionsList) this.list;
		final SpatialAudioConfig c = this.working;

		// —— 状态 ——
		options.addTall(new StatusWidget(310), StatusWidget.HEIGHT);
		options.addSmall(
			Button.builder(Component.translatable("spatialaudio.button.recenter"), b -> HeadTrackingManager.get().recenter()).build(),
			Button.builder(Component.translatable("spatialaudio.button.restart_tracking"), b -> {
				this.applyChanges();
				HeadTrackingManager.get().start(ConfigManager.get());
			}).build()
		);

		// —— 通用 ——
		options.addHeader(Component.translatable("spatialaudio.config.section.general"));
		options.addSmall(
			OptionFactory.toggle(OPTION + "enabled", c.enabled, v -> c.enabled = v),
			OptionFactory.cycle(OPTION + "hrtf_mode", Arrays.asList(HrtfMode.values()), c.hrtfMode,
				v -> Component.translatable(v.translationKey()), v -> c.hrtfMode = v)
		);
		final List<String> hrtfChoices = new ArrayList<>();
		hrtfChoices.add("");
		hrtfChoices.addAll(AudioSystem.get().availableHrtfs());
		if (!c.hrtfName.isEmpty() && !hrtfChoices.contains(c.hrtfName)) {
			hrtfChoices.add(c.hrtfName);
		}
		options.addBig(OptionFactory.cycle(OPTION + "hrtf_name", hrtfChoices, c.hrtfName,
			v -> v.isEmpty() ? Component.translatable("spatialaudio.hrtf_name.default") : Component.literal(v), v -> c.hrtfName = v));

		// —— 头部追踪 ——
		options.addHeader(Component.translatable("spatialaudio.config.section.tracking"));
		options.addSmall(
			OptionFactory.cycle(OPTION + "tracking_source", Arrays.asList(HeadTrackingSource.values()), c.trackingSource,
				v -> Component.translatable(v.translationKey()), v -> c.trackingSource = v),
			OptionFactory.percent(OPTION + "tracking_strength", c.trackingStrength, 100, v -> c.trackingStrength = v)
		);
		options.addSmall(
			OptionFactory.toggle(OPTION + "track_yaw", c.trackYaw, v -> c.trackYaw = v),
			OptionFactory.toggle(OPTION + "track_pitch", c.trackPitch, v -> c.trackPitch = v),
			OptionFactory.toggle(OPTION + "track_roll", c.trackRoll, v -> c.trackRoll = v),
			OptionFactory.cycle(OPTION + "recenter_mode", Arrays.asList(RecenterMode.values()), c.recenterMode,
				v -> Component.translatable(v.translationKey()), v -> c.recenterMode = v),
			OptionFactory.toggle(OPTION + "invert_yaw", c.invertYaw, v -> c.invertYaw = v),
			OptionFactory.toggle(OPTION + "invert_pitch", c.invertPitch, v -> c.invertPitch = v),
			OptionFactory.toggle(OPTION + "invert_roll", c.invertRoll, v -> c.invertRoll = v),
			OptionFactory.toggle(OPTION + "auto_recenter", c.autoRecenterOnConnect, v -> c.autoRecenterOnConnect = v),
			OptionFactory.toggle(OPTION + "drift_correction", c.driftCorrection, v -> c.driftCorrection = v),
			OptionFactory.integer(OPTION + "drift_seconds", 5, 120, Math.round(c.driftCorrectionSeconds),
				"spatialaudio.unit.seconds", v -> c.driftCorrectionSeconds = v),
			OptionFactory.integer(OPTION + "prediction", 0, 120, Math.round(c.predictionMs),
				"spatialaudio.unit.milliseconds", v -> c.predictionMs = v),
			OptionFactory.integer(OPTION + "listener_rate", 20, 250, c.listenerUpdateRate,
				"spatialaudio.unit.hertz", v -> c.listenerUpdateRate = v),
			OptionFactory.decimal(OPTION + "smoothing_cutoff", 0.1f, 5.0f, 0.1f, c.smoothingMinCutoff, "%.1f",
				"spatialaudio.unit.hertz", v -> c.smoothingMinCutoff = v),
			OptionFactory.decimal(OPTION + "smoothing_beta", 0.0f, 2.0f, 0.05f, c.smoothingBeta, "%.2f",
				"spatialaudio.unit.plain", v -> c.smoothingBeta = v),
			OptionFactory.toggle(OPTION + "tracker_translation", c.useTrackerTranslation, v -> c.useTrackerTranslation = v),
			OptionFactory.percent(OPTION + "translation_scale", c.translationScale, 300, v -> c.translationScale = v)
		);
		final EditBox portBox = new EditBox(this.font, 150, 20, Component.translatable(OPTION + "udp_port"));
		portBox.setMaxLength(5);
		portBox.setValue(Integer.toString(c.udpPort));
		portBox.setResponder(text -> {
			try {
				final int port = Integer.parseInt(text.trim());
				if (port >= 1 && port <= 65535) {
					c.udpPort = port;
				}
			} catch (NumberFormatException ignored) {
				// 输入不完整时保留原值。
			}
		});
		options.addSmall(new StringWidget(150, 20, Component.translatable(OPTION + "udp_port"), this.font), portBox);
		final OptionInstance<Boolean> allInterfaces = OptionFactory.toggle(OPTION + "udp_all_interfaces", c.udpListenAllInterfaces,
			v -> c.udpListenAllInterfaces = v);
		options.addSmall(
			allInterfaces.createButton(this.options),
			allInterfaces,
			Button.builder(Component.translatable("spatialaudio.button.open_helper_folder"),
				b -> Blaze3D.openPath(MacHelperLauncher.helperDirectory())).build()
		);

		// —— 声场 ——
		options.addHeader(Component.translatable("spatialaudio.config.section.soundstage"));
		options.addSmall(
			OptionFactory.toggle(OPTION + "anchor_to_screen", c.anchorToScreen, v -> c.anchorToScreen = v),
			OptionFactory.toggle(OPTION + "spatialize_stereo", c.spatializeStereo, v -> c.spatializeStereo = v)
		);
		options.addBig(Button.builder(Component.translatable("spatialaudio.button.categories"),
			b -> this.minecraft.gui.setScreen(new CategorySettingsScreen(this, this.working))).width(310).build());

		// —— 声学模拟 ——
		options.addHeader(Component.translatable("spatialaudio.config.section.acoustics"));
		options.addSmall(
			OptionFactory.toggle(OPTION + "acoustics", c.acousticsEnabled, v -> c.acousticsEnabled = v),
			OptionFactory.toggle(OPTION + "respect_sound_physics", c.respectSoundPhysicsMod, v -> c.respectSoundPhysicsMod = v),
			OptionFactory.toggle(OPTION + "reverb", c.reverbEnabled, v -> c.reverbEnabled = v),
			OptionFactory.percent(OPTION + "reverb_gain", c.reverbGain, 200, v -> c.reverbGain = v),
			OptionFactory.toggle(OPTION + "echo", c.echoEnabled, v -> c.echoEnabled = v),
			OptionFactory.percent(OPTION + "echo_gain", c.echoGain, 200, v -> c.echoGain = v),
			OptionFactory.toggle(OPTION + "occlusion", c.occlusionEnabled, v -> c.occlusionEnabled = v),
			OptionFactory.percent(OPTION + "occlusion_strength", c.occlusionStrength, 300, v -> c.occlusionStrength = v),
			OptionFactory.toggle(OPTION + "redirection", c.soundRedirection, v -> c.soundRedirection = v),
			OptionFactory.toggle(OPTION + "medium_filter", c.mediumFilter, v -> c.mediumFilter = v),
			OptionFactory.toggle(OPTION + "air_absorption", c.airAbsorption, v -> c.airAbsorption = v),
			OptionFactory.percent(OPTION + "air_absorption_factor", c.airAbsorptionFactor, 500, v -> c.airAbsorptionFactor = v),
			OptionFactory.toggle(OPTION + "doppler", c.dopplerEnabled, v -> c.dopplerEnabled = v),
			OptionFactory.percent(OPTION + "doppler_factor", c.dopplerFactor, 200, v -> c.dopplerFactor = v)
		);

		// —— 性能 ——
		options.addHeader(Component.translatable("spatialaudio.config.section.performance"));
		options.addSmall(
			OptionFactory.integer(OPTION + "probe_rays", 16, 256, c.probeRays, "spatialaudio.unit.rays", v -> c.probeRays = v),
			OptionFactory.integer(OPTION + "probe_distance", 8, 128, c.probeDistance, "spatialaudio.unit.blocks", v -> c.probeDistance = v),
			OptionFactory.integer(OPTION + "echo_distance", 16, 160, c.echoDistance, "spatialaudio.unit.blocks", v -> c.echoDistance = v),
			OptionFactory.decimal(OPTION + "diffraction_cells", 0.0f, 10000.0f, 500.0f, c.diffractionCells, "%.0f",
				"spatialaudio.unit.blocks", v -> c.diffractionCells = Math.round(v)),
			OptionFactory.decimal(OPTION + "budget", 0.2f, 10.0f, 0.1f, c.acousticsBudgetMs, "%.1f",
				"spatialaudio.unit.milliseconds", v -> c.acousticsBudgetMs = v)
		);

		// —— 界面 ——
		options.addHeader(Component.translatable("spatialaudio.config.section.interface"));
		options.addSmall(
			OptionFactory.toggle(OPTION + "show_hud", c.showHud, v -> c.showHud = v),
			OptionFactory.toggle(OPTION + "setup_hints", c.showSetupHints, v -> c.showSetupHints = v)
		);
		options.addSmall(
			Button.builder(Component.translatable("spatialaudio.button.open_config_folder"),
				b -> Blaze3D.openPath(ConfigManager.path().getParent())).build(),
			Button.builder(Component.translatable("spatialaudio.button.reset"), b -> {
				ConfigManager.resetToDefaults();
				SpatialAudioClient.onConfigChanged(this.before, ConfigManager.get());
				this.before = ConfigManager.snapshot();
				this.discardChanges = true;
				this.minecraft.gui.setScreen(new SpatialAudioConfigScreen(this.lastScreen));
			}).build()
		);
	}

	@Override
	public void removed() {
		if (!this.discardChanges) {
			this.applyChanges();
		}
	}

	/**
	 * 把工作副本应用为正式配置，并根据差异重启相关子系统。
	 */
	private void applyChanges() {
		if (this.list != null) {
			this.list.applyUnsavedChanges();
		}
		final SpatialAudioConfig applied = ConfigManager.copy(this.working);
		ConfigManager.replace(applied);
		SpatialAudioClient.onConfigChanged(this.before, applied);
		this.before = ConfigManager.snapshot();
	}
}
