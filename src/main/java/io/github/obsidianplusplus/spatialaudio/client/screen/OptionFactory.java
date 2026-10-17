package io.github.obsidianplusplus.spatialaudio.client.screen;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * 构造设置界面选项（{@link OptionInstance}）的工具方法。
 *
 * <p>所有选项都直接绑定到“工作副本”配置对象的字段：界面上的修改立即写入副本，
 * 关闭界面时再整体应用。每个选项都使用 {@code 键名.tooltip} 作为悬停说明。
 */
final class OptionFactory {
	private OptionFactory() {
	}

	/**
	 * 开关选项。
	 *
	 * @param key 翻译键
	 * @param value 初始值
	 * @param setter 写入回调
	 * @return 选项
	 */
	static OptionInstance<Boolean> toggle(final String key, final boolean value, final Consumer<Boolean> setter) {
		return OptionInstance.createBoolean(key, tooltip(key), value, setter::accept);
	}

	/**
	 * 百分比滑块。
	 *
	 * @param key 翻译键
	 * @param value 初始值（1.0 表示 100%）
	 * @param maxPercent 最大百分比
	 * @param setter 写入回调
	 * @return 选项
	 */
	static OptionInstance<Integer> percent(final String key, final float value, final int maxPercent, final Consumer<Float> setter) {
		return new OptionInstance<>(
			key,
			tooltip(key),
			(caption, v) -> Options.genericValueLabel(caption, Component.literal(v + "%")),
			new OptionInstance.IntRange(0, maxPercent),
			Math.clamp(Math.round(value * 100.0f), 0, maxPercent),
			v -> setter.accept(v / 100.0f)
		);
	}

	/**
	 * 整数滑块。
	 *
	 * @param key 翻译键
	 * @param min 最小值
	 * @param max 最大值
	 * @param value 初始值
	 * @param unitKey 单位翻译键（带一个 %s 参数）
	 * @param setter 写入回调
	 * @return 选项
	 */
	static OptionInstance<Integer> integer(final String key, final int min, final int max, final int value, final String unitKey,
			final Consumer<Integer> setter) {
		return new OptionInstance<>(
			key,
			tooltip(key),
			(caption, v) -> Options.genericValueLabel(caption, Component.translatable(unitKey, v)),
			new OptionInstance.IntRange(min, max),
			Math.clamp(value, min, max),
			setter::accept
		);
	}

	/**
	 * 小数滑块：内部以整数步进表示，显示时按缩放系数还原。
	 *
	 * @param key 翻译键
	 * @param min 最小值
	 * @param max 最大值
	 * @param step 步长
	 * @param value 初始值
	 * @param format 显示格式（例如 "%.1f"）
	 * @param unitKey 单位翻译键（带一个 %s 参数）
	 * @param setter 写入回调
	 * @return 选项
	 */
	static OptionInstance<Integer> decimal(final String key, final float min, final float max, final float step, final float value,
			final String format, final String unitKey, final Consumer<Float> setter) {
		final int steps = Math.round((max - min) / step);
		final int initial = Math.clamp(Math.round((value - min) / step), 0, steps);
		return new OptionInstance<>(
			key,
			tooltip(key),
			(caption, v) -> Options.genericValueLabel(caption,
				Component.translatable(unitKey, String.format(Locale.ROOT, format, min + v * step))),
			new OptionInstance.IntRange(0, steps),
			initial,
			v -> setter.accept(min + v * step)
		);
	}

	/**
	 * 循环切换选项（用于枚举等离散值）。
	 *
	 * @param key 翻译键
	 * @param values 可选值
	 * @param value 初始值
	 * @param label 值的显示文本
	 * @param setter 写入回调
	 * @param <T> 值类型
	 * @return 选项
	 */
	static <T> OptionInstance<T> cycle(final String key, final List<T> values, final T value, final Function<T, Component> label,
			final Consumer<T> setter) {
		final Codec<T> codec = Codec.INT.xmap(i -> values.get(Math.clamp(i, 0, values.size() - 1)), values::indexOf);
		return new OptionInstance<>(
			key,
			tooltip(key),
			// 循环按钮会自动在前面加上“标题: ”，这里只返回值本身。
			(caption, v) -> label.apply(v),
			new OptionInstance.Enum<>(values, codec),
			values.contains(value) ? value : values.getFirst(),
			setter::accept
		);
	}

	/**
	 * 循环切换选项，悬停说明在通用说明之后附加当前取值的专属说明（用于各取值差别很大的选项，例如渲染方式）。
	 *
	 * @param key 翻译键
	 * @param values 可选值
	 * @param value 初始值
	 * @param label 值的显示文本
	 * @param valueTooltip 当前取值的专属说明
	 * @param setter 写入回调
	 * @param <T> 值类型
	 * @return 选项
	 */
	static <T> OptionInstance<T> cycle(final String key, final List<T> values, final T value, final Function<T, Component> label,
			final Function<T, Component> valueTooltip, final Consumer<T> setter) {
		final Codec<T> codec = Codec.INT.xmap(i -> values.get(Math.clamp(i, 0, values.size() - 1)), values::indexOf);
		final Component general = Component.translatable(key + ".tooltip");
		return new OptionInstance<>(
			key,
			// 循环按钮每次切换取值都会重新调用，悬停说明随之更新。
			v -> Tooltip.create(Component.empty().append(general).append("\n\n").append(valueTooltip.apply(v))),
			(caption, v) -> label.apply(v),
			new OptionInstance.Enum<>(values, codec),
			values.contains(value) ? value : values.getFirst(),
			setter::accept
		);
	}

	/**
	 * 生成悬停说明。
	 *
	 * @param key 选项翻译键
	 * @param <T> 值类型
	 * @return 悬停说明提供器
	 */
	private static <T> OptionInstance.TooltipSupplier<T> tooltip(final String key) {
		return OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip"));
	}
}
