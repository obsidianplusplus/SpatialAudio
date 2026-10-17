package io.github.obsidianplusplus.spatialaudio.client.screen;

import io.github.obsidianplusplus.spatialaudio.client.StatusLines;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 设置界面顶部的实时状态面板：每帧刷新头部追踪状态、姿态角、HRTF 与声学环境信息。
 */
public final class StatusWidget extends AbstractWidget {
	/** 面板高度。 */
	public static final int HEIGHT = 66;

	/** 行高。 */
	private static final int LINE_HEIGHT = 10;

	/** 内边距。 */
	private static final int PADDING = 4;

	/**
	 * 创建状态面板。
	 *
	 * @param width 宽度
	 */
	public StatusWidget(final int width) {
		super(0, 0, width, HEIGHT - 4, Component.translatable("spatialaudio.config.status"));
		this.active = false;
	}

	@Override
	protected void extractWidgetRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		final Font font = Minecraft.getInstance().font;
		final int x = this.getX();
		final int y = this.getY();
		graphics.fill(x, y, x + this.getWidth(), y + this.getHeight(), 0x70000000);
		final int maxLines = (this.getHeight() - PADDING) / LINE_HEIGHT;
		int line = 0;
		for (final StatusLines.Line status : StatusLines.build(true)) {
			final List<FormattedCharSequence> wrapped = font.split(status.text(), this.getWidth() - PADDING * 2);
			for (final FormattedCharSequence part : wrapped) {
				if (line >= maxLines) {
					return;
				}
				graphics.text(font, part, x + PADDING, y + PADDING + line * LINE_HEIGHT, status.color(), true);
				line++;
			}
		}
	}

	@Override
	protected void updateWidgetNarration(final NarrationElementOutput output) {
		final StringBuilder text = new StringBuilder();
		for (final StatusLines.Line status : StatusLines.build(false)) {
			text.append(status.text().getString()).append(". ");
		}
		output.add(NarratedElementType.TITLE, Component.literal(text.toString()));
	}
}
