package io.github.obsidianplusplus.spatialaudio.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRendererStatus;
import io.github.obsidianplusplus.spatialaudio.config.AppleBedFormat;
import io.github.obsidianplusplus.spatialaudio.config.HeadTrackingSource;
import io.github.obsidianplusplus.spatialaudio.config.HrtfMode;
import io.github.obsidianplusplus.spatialaudio.config.RecenterMode;
import io.github.obsidianplusplus.spatialaudio.config.SpatialRenderer;
import io.github.obsidianplusplus.spatialaudio.headtracking.TrackerStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 语言文件完整性测试：中英文键集合与占位符数量一致，且源码与枚举引用的所有键都存在。
 */
class LanguageFilesTest {
	/** 源码中的翻译键字面量。 */
	private static final Pattern KEY_LITERAL = Pattern.compile("\"((?:key\\.)?spatialaudio\\.[a-z0-9_.]+[a-z0-9_])\"");

	/** 选项键（OPTION + "xxx"）。 */
	private static final Pattern OPTION_LITERAL = Pattern.compile("OPTION \\+ \"([a-z0-9_]+)\"");

	/** 类别选项键（prefix + "xxx"）。 */
	private static final Pattern CATEGORY_LITERAL = Pattern.compile("prefix \\+ \"([a-z0-9_]+)\"");

	/** 不属于翻译键的字面量。 */
	private static final Set<String> NOT_TRANSLATION_KEYS = Set.of("spatialaudio.debug");

	/** 格式化占位符（%s、%d、%1$s 等，不含转义的 %%）。 */
	private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[sd]");

	/**
	 * 读取语言文件。
	 *
	 * @param name 语言代码
	 * @return JSON 对象
	 * @throws IOException 读取失败
	 */
	private static JsonObject load(final String name) throws IOException {
		try (InputStream in = LanguageFilesTest.class.getResourceAsStream("/assets/spatialaudio/lang/" + name + ".json")) {
			assertNotNull(in, "缺少语言文件 " + name);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	/**
	 * 中英文语言文件的键集合完全一致。
	 *
	 * @throws IOException 读取失败
	 */
	@Test
	void languagesHaveSameKeys() throws IOException {
		assertEquals(new TreeSet<>(load("en_us").keySet()), new TreeSet<>(load("zh_cn").keySet()));
	}

	/**
	 * 同一个键在中英文中的占位符数量一致（否则某种语言会丢失参数或显示原始的 %s）。
	 *
	 * @throws IOException 读取失败
	 */
	@Test
	void placeholdersMatch() throws IOException {
		final JsonObject zh = load("zh_cn");
		final JsonObject en = load("en_us");
		final Set<String> mismatched = new TreeSet<>();
		for (final String key : zh.keySet()) {
			if (en.has(key) && countPlaceholders(zh.get(key).getAsString()) != countPlaceholders(en.get(key).getAsString())) {
				mismatched.add(key);
			}
		}
		assertTrue(mismatched.isEmpty(), "以下键的中英文占位符数量不一致：" + mismatched);
	}

	/**
	 * 新增渲染方式相关的枚举都有完整的翻译（包括渲染方式的专属悬停说明与状态的排障提示）。
	 *
	 * @throws IOException 读取失败
	 */
	@Test
	void rendererKeysExist() throws IOException {
		final Set<String> keys = load("zh_cn").keySet();
		final Set<String> missing = new TreeSet<>();
		for (final String key : rendererKeys()) {
			if (!keys.contains(key)) {
				missing.add(key);
			}
		}
		assertTrue(missing.isEmpty(), "语言文件缺少以下渲染方式相关的键：" + missing);
	}

	/**
	 * 渲染方式、Apple 声场格式与渲染状态枚举引用的全部键。
	 *
	 * @return 键列表
	 */
	private static List<String> rendererKeys() {
		final List<String> keys = new ArrayList<>();
		for (final SpatialRenderer renderer : SpatialRenderer.values()) {
			keys.add(renderer.translationKey());
			// 设置界面在通用说明之后附加当前取值的专属说明。
			keys.add(renderer.translationKey() + ".tooltip");
		}
		for (final AppleBedFormat format : AppleBedFormat.values()) {
			keys.add(format.translationKey());
		}
		for (final AppleRendererStatus status : AppleRendererStatus.values()) {
			keys.add(status.translationKey());
			keys.add(status.hintKey());
		}
		return keys;
	}

	/**
	 * 源码、枚举与界面选项引用的所有键都存在于语言文件中。
	 *
	 * @throws IOException 读取失败
	 */
	@Test
	void allReferencedKeysExist() throws IOException {
		final Set<String> keys = load("zh_cn").keySet();
		final Set<String> required = new HashSet<>();
		for (final HrtfMode mode : HrtfMode.values()) {
			required.add(mode.translationKey());
		}
		for (final HeadTrackingSource source : HeadTrackingSource.values()) {
			required.add(source.translationKey());
		}
		for (final RecenterMode mode : RecenterMode.values()) {
			required.add(mode.translationKey());
		}
		for (final TrackerStatus status : TrackerStatus.values()) {
			required.add(status.translationKey());
			required.add(status.hintKey());
		}
		required.addAll(rendererKeys());
		final Path sources = Path.of("src/main/java");
		try (Stream<Path> files = Files.walk(sources)) {
			for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				final String text = Files.readString(file, StandardCharsets.UTF_8);
				collect(KEY_LITERAL, text, "", "", required);
				collect(OPTION_LITERAL, text, "spatialaudio.option.", "", required);
				collect(OPTION_LITERAL, text, "spatialaudio.option.", ".tooltip", required);
				collect(CATEGORY_LITERAL, text, "spatialaudio.option.category.", "", required);
				collect(CATEGORY_LITERAL, text, "spatialaudio.option.category.", ".tooltip", required);
			}
		}
		required.removeAll(NOT_TRANSLATION_KEYS);
		final Set<String> missing = new TreeSet<>(required);
		missing.removeAll(keys);
		assertTrue(missing.isEmpty(), "语言文件缺少以下键：" + missing);
	}

	/**
	 * 统计文本中的格式化占位符数量。
	 *
	 * @param text 文本
	 * @return 占位符数量
	 */
	private static int countPlaceholders(final String text) {
		final Matcher matcher = PLACEHOLDER.matcher(text.replace("%%", ""));
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	/**
	 * 收集正则匹配到的键。
	 *
	 * @param pattern 正则
	 * @param text 源码文本
	 * @param prefix 前缀
	 * @param suffix 后缀
	 * @param out 输出集合
	 */
	private static void collect(final Pattern pattern, final String text, final String prefix, final String suffix, final Set<String> out) {
		final Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			out.add(prefix + matcher.group(1) + suffix);
		}
	}
}
