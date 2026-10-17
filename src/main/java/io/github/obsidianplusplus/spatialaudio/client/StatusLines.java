package io.github.obsidianplusplus.spatialaudio.client;

import io.github.obsidianplusplus.spatialaudio.acoustics.AcousticsManager;
import io.github.obsidianplusplus.spatialaudio.acoustics.EnvironmentSnapshot;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import io.github.obsidianplusplus.spatialaudio.headtracking.TrackerStatus;
import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * 生成状态信息文本，供调试叠加层、设置界面与 /spatialaudio status 命令共用。
 */
public final class StatusLines {
	private StatusLines() {
	}

	/**
	 * 一行带颜色的状态文本。
	 *
	 * @param text 文本
	 * @param color ARGB 颜色
	 */
	public record Line(Component text, int color) {
	}

	/**
	 * 构建状态行。
	 *
	 * @param includeHint 是否附带排障提示
	 * @return 状态行列表
	 */
	public static List<Line> build(final boolean includeHint) {
		final List<Line> lines = new ArrayList<>();
		final SpatialAudioConfig config = ConfigManager.get();
		final AudioSystem audio = AudioSystem.get();
		final HeadTrackingManager tracking = HeadTrackingManager.get();
		final TrackerStatus status = tracking.status();

		if (!config.enabled) {
			lines.add(new Line(Component.translatable("spatialaudio.status_line.mod_disabled"), 0xFF9E9E9E));
			return lines;
		}

		final double rate = tracking.sampleRate();
		final Component rateText = rate > 0.0 ? Component.literal(String.format(Locale.ROOT, " · %.0f Hz", rate)) : Component.empty();
		lines.add(new Line(Component.translatable("spatialaudio.status_line.tracking",
			Component.translatable(status.translationKey()), rateText), status.color()));

		final Quat head = audio.listener().head();
		final double[] euler = head.toEulerZXY();
		lines.add(new Line(Component.translatable("spatialaudio.status_line.pose",
			String.format(Locale.ROOT, "%+.1f°", Math.toDegrees(euler[0])),
			String.format(Locale.ROOT, "%+.1f°", Math.toDegrees(euler[1])),
			String.format(Locale.ROOT, "%+.1f°", Math.toDegrees(euler[2]))), 0xFFE0E0E0));

		final AudioSystem.HrtfInfo hrtf = audio.hrtf();
		final Component hrtfName = hrtf.name().isEmpty() ? Component.literal("-") : Component.literal(hrtf.name());
		lines.add(new Line(Component.translatable("spatialaudio.status_line.hrtf",
			Component.translatable(hrtf.statusKey()), hrtfName, Component.literal(audio.deviceName())),
			hrtf.enabled() ? 0xFF4CAF50 : 0xFFFFC107));

		final Component acousticsState;
		if (!config.acousticsEnabled) {
			acousticsState = Component.translatable("spatialaudio.acoustics_state.off");
		} else if (config.respectSoundPhysicsMod && audio.soundPhysicsPresent()) {
			acousticsState = Component.translatable("spatialaudio.acoustics_state.sound_physics");
		} else if (!audio.acousticsActive(config)) {
			acousticsState = Component.translatable("spatialaudio.acoustics_state.unavailable");
		} else {
			acousticsState = Component.translatable("spatialaudio.acoustics_state.on");
		}
		final EnvironmentSnapshot env = AcousticsManager.get().environment();
		lines.add(new Line(Component.translatable("spatialaudio.status_line.acoustics",
			acousticsState,
			String.format(Locale.ROOT, "%.0f%%", env.enclosure() * 100.0f),
			String.format(Locale.ROOT, "%.2f", env.rt60()),
			String.format(Locale.ROOT, "%.0f", env.echoDistance()),
			audio.sources().size()), 0xFFB0BEC5));

		if (includeHint && !status.isActive() && status != TrackerStatus.DISABLED) {
			Component hint = Component.translatable(status.hintKey());
			final String error = tracking.helper().lastError();
			if ((status == TrackerStatus.HELPER_FAILED || status == TrackerStatus.HELPER_NOT_FOUND) && !error.isEmpty()) {
				hint = Component.empty().append(hint).append(Component.literal(" (" + error + ")"));
			}
			lines.add(new Line(hint, 0xFFFFE082));
		}
		return lines;
	}
}
