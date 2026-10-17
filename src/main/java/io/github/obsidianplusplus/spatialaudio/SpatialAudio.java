package io.github.obsidianplusplus.spatialaudio;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组全局常量与日志入口。
 *
 * <p>本类不包含任何状态，只提供模组 ID、日志器以及若干全局开关，供其它模块统一引用。
 */
public final class SpatialAudio {
	/** 模组 ID，必须与 fabric.mod.json 中的 id 保持一致。 */
	public static final String MOD_ID = "spatialaudio";

	/** 模组显示名称。 */
	public static final String MOD_NAME = "SpatialAudio";

	/** 模组统一日志器。 */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

	/** 是否输出调试日志（通过 JVM 参数 -Dspatialaudio.debug=true 打开）。 */
	public static final boolean DEBUG = Boolean.getBoolean("spatialaudio.debug");

	private SpatialAudio() {
	}

	/**
	 * 创建属于本模组命名空间的标识符。
	 *
	 * @param path 路径部分
	 * @return 形如 spatialaudio:path 的标识符
	 */
	public static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/**
	 * 输出调试日志，仅在调试模式下生效。
	 *
	 * @param message 日志模板
	 * @param args 模板参数
	 */
	public static void debug(final String message, final Object... args) {
		if (DEBUG) {
			LOGGER.info("[调试] " + message, args);
		}
	}
}
