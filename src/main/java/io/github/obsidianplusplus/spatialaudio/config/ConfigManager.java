package io.github.obsidianplusplus.spatialaudio.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 配置文件的读取、保存与快照管理。
 *
 * <p>配置以 JSON 存放在 {@code .minecraft/config/spatialaudio.json}。
 * 读取失败时会把损坏的文件改名为 {@code .broken} 备份，并回退到默认配置，保证游戏可以正常启动。
 */
public final class ConfigManager {
	/** 带缩进输出的 Gson 实例。 */
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** 配置文件名。 */
	private static final String FILE_NAME = SpatialAudio.MOD_ID + ".json";

	/** 当前生效的配置，使用 volatile 保证音频线程读取到最新引用。 */
	private static volatile SpatialAudioConfig current = new SpatialAudioConfig();

	private ConfigManager() {
	}

	/**
	 * 获取当前配置。
	 *
	 * @return 当前配置对象（请勿在其它线程长期缓存）
	 */
	public static SpatialAudioConfig get() {
		return current;
	}

	/**
	 * 配置文件的完整路径。
	 *
	 * @return 路径
	 */
	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/**
	 * 从磁盘读取配置；文件不存在时写入默认配置。
	 */
	public static void load() {
		final Path file = path();
		SpatialAudioConfig loaded = null;
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, SpatialAudioConfig.class);
			} catch (IOException | JsonParseException e) {
				SpatialAudio.LOGGER.error("读取配置文件失败，已备份并恢复默认配置：{}", file, e);
				backupBrokenFile(file);
			}
		}
		if (loaded == null) {
			loaded = new SpatialAudioConfig();
		}
		loaded.sanitize();
		current = loaded;
		save();
	}

	/**
	 * 把当前配置写回磁盘（先写临时文件再原子替换，避免写入中途崩溃导致文件损坏）。
	 */
	public static void save() {
		final Path file = path();
		try {
			Files.createDirectories(file.getParent());
			final Path temp = file.resolveSibling(FILE_NAME + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(current, writer);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SpatialAudio.LOGGER.error("保存配置文件失败：{}", file, e);
		}
	}

	/**
	 * 创建当前配置的深拷贝快照，用于比较修改前后的差异。
	 *
	 * @return 深拷贝
	 */
	public static SpatialAudioConfig snapshot() {
		return copy(current);
	}

	/**
	 * 深拷贝任意配置对象。
	 *
	 * @param config 源配置
	 * @return 深拷贝
	 */
	public static SpatialAudioConfig copy(final SpatialAudioConfig config) {
		final SpatialAudioConfig copy = GSON.fromJson(GSON.toJson(config), SpatialAudioConfig.class);
		copy.sanitize();
		return copy;
	}

	/**
	 * 用新的配置替换当前配置并保存。
	 *
	 * @param config 新配置（会被修正到合法范围）
	 */
	public static void replace(final SpatialAudioConfig config) {
		config.sanitize();
		current = config;
		save();
	}

	/**
	 * 用默认值重置全部配置并保存。
	 */
	public static void resetToDefaults() {
		final SpatialAudioConfig fresh = new SpatialAudioConfig();
		fresh.sanitize();
		current = fresh;
		save();
	}

	/**
	 * 把损坏的配置文件改名备份。
	 *
	 * @param file 损坏的文件
	 */
	private static void backupBrokenFile(final Path file) {
		try {
			Files.move(file, file.resolveSibling(FILE_NAME + ".broken"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			SpatialAudio.LOGGER.warn("无法备份损坏的配置文件：{}", file, e);
		}
	}
}
