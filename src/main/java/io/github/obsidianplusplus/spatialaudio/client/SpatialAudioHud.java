package io.github.obsidianplusplus.spatialaudio.client;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 调试叠加层：在屏幕左上角显示头部追踪状态、实时姿态、HRTF 与声学环境。
 *
 * <p>用于校准方向：向左转头时偏航应为正值、抬头时俯仰应为正值、向右歪头时横滚应为正值，
 * 若方向相反可在设置中反转对应轴。
 */
public final class SpatialAudioHud {
	/** 文字行高。 */
	private static final int LINE_HEIGHT = 10;

	/** 边距。 */
	private static final int PADDING = 4;

	private SpatialAudioHud() {
	}

	/**
	 * 注册叠加层。
	 */
	public static void register() {
		HudElementRegistry.addLast(SpatialAudio.id("status"), (graphics, deltaTracker) -> render(graphics));
	}

	/**
	 * 绘制叠加层。
	 *
	 * @param graphics 绘制上下文
	 */
	private static void render(final GuiGraphicsExtractor graphics) {
		final Minecraft minecraft = Minecraft.getInstance();
		if (!ConfigManager.get().showHud || minecraft.gui.hud.isHidden()) {
			return;
		}
		final Font font = minecraft.font;
		final List<StatusLines.Line> lines = StatusLines.build(true);
		int width = 0;
		for (final StatusLines.Line line : lines) {
			width = Math.max(width, font.width(line.text()));
		}
		final int x = PADDING;
		final int y = PADDING;
		graphics.fill(x - 2, y - 2, x + width + 2, y + lines.size() * LINE_HEIGHT, 0x90000000);
		for (int i = 0; i < lines.size(); i++) {
			final StatusLines.Line line = lines.get(i);
			graphics.text(font, line.text(), x, y + i * LINE_HEIGHT, line.color(), true);
		}
	}
}
