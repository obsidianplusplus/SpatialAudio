package io.github.obsidianplusplus.spatialaudio.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;

/**
 * 扩展原版选项列表，允许插入高度大于普通行的控件（例如多行状态面板）。
 */
public final class SpatialOptionsList extends OptionsList {
	/** 所属界面。 */
	private final OptionsSubScreen owner;

	/**
	 * 创建列表。
	 *
	 * @param minecraft 客户端实例
	 * @param width 宽度
	 * @param screen 所属界面
	 */
	public SpatialOptionsList(final Minecraft minecraft, final int width, final OptionsSubScreen screen) {
		super(minecraft, width, screen);
		this.owner = screen;
	}

	/**
	 * 添加一个占满整行、指定高度的控件。
	 *
	 * @param widget 控件
	 * @param height 行高
	 */
	public void addTall(final AbstractWidget widget, final int height) {
		this.addEntry(OptionsList.Entry.big(widget, this.owner), height);
	}
}
