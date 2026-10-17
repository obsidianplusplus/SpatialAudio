package io.github.obsidianplusplus.spatialaudio.config;

import java.util.Locale;

/**
 * Apple 空间音频渲染方式下，OpenAL 交给 Apple 渲染引擎的多声道声场格式。
 *
 * <p>Ambisonics（ACN 通道顺序、SN3D 归一化）描述整个球面声场，包含高度信息，阶数越高方向越精确；
 * 7.1 只覆盖水平面。Apple 的 AVAudioEnvironmentNode 支持最高 3 阶 Ambisonics 与常见的声道布局。
 */
public enum AppleBedFormat {
	/** 3 阶 Ambisonics，16 声道（默认，方向最精确）。 */
	AMBISONICS_3(16, 3),
	/** 2 阶 Ambisonics，9 声道。 */
	AMBISONICS_2(9, 2),
	/** 1 阶 Ambisonics，4 声道（方向较模糊，数据量最小）。 */
	AMBISONICS_1(4, 1),
	/** 7.1 环绕声，8 声道（仅水平面，顺序 FL FR FC LFE BL BR SL SR）。 */
	SURROUND_7_1(8, 0);

	/** 声道数。 */
	private final int channels;

	/** Ambisonics 阶数；非 Ambisonics 格式为 0。 */
	private final int ambisonicOrder;

	/**
	 * 创建格式常量。
	 *
	 * @param channels 声道数
	 * @param ambisonicOrder Ambisonics 阶数，非 Ambisonics 为 0
	 */
	AppleBedFormat(final int channels, final int ambisonicOrder) {
		this.channels = channels;
		this.ambisonicOrder = ambisonicOrder;
	}

	/**
	 * 声道数。
	 *
	 * @return 声道数
	 */
	public int channels() {
		return this.channels;
	}

	/**
	 * 是否为 Ambisonics 格式。
	 *
	 * @return 是 Ambisonics 时返回 true
	 */
	public boolean ambisonic() {
		return this.ambisonicOrder > 0;
	}

	/**
	 * Ambisonics 阶数。
	 *
	 * @return 阶数；非 Ambisonics 格式为 0
	 */
	public int ambisonicOrder() {
		return this.ambisonicOrder;
	}

	/**
	 * 获取用于界面显示的翻译键。
	 *
	 * @return 翻译键
	 */
	public String translationKey() {
		return "spatialaudio.apple_bed." + this.name().toLowerCase(Locale.ROOT);
	}
}
