package io.github.obsidianplusplus.spatialaudio.headtracking;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRenderer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 负责准备并启动 macOS 上的 AirPods 头部追踪助手（SpatialAudioHeadTracker.app）。
 *
 * <p>为什么需要独立助手：Apple 规定使用 {@code CMHeadphoneMotionManager} 的进程必须在 Info.plist 中声明
 * {@code NSMotionUsageDescription}，否则系统会在开始读取耳机运动数据时直接终止该进程。
 * Minecraft 运行在启动器拉起的 Java 进程中，无法添加这一声明，若在游戏进程内直接调用会导致游戏崩溃。
 * 因此本模组把读取 AirPods 姿态的工作交给一个带有完整 Info.plist 的小型 .app，
 * 通过 {@code open} 命令启动（使其成为独立的“责任进程”，系统会以它的名义弹出授权提示），
 * 再经本机 UDP 把姿态发送回游戏。
 *
 * <p>助手来源优先级：
 * <ol>
 *   <li>用户自备的助手：{@code config/spatialaudio/helper/custom/SpatialAudioHeadTracker.app}（原样启动，不解压、不重新签名，
 *       用于带有 Apple 头部追踪与个性化空间音频授权的 Developer ID 签名版本）；</li>
 *   <li>jar 内预编译的通用二进制（在 macOS 上构建或通过 CI 发布时包含）；</li>
 *   <li>jar 内附带的 Swift 源码，在检测到 Xcode 命令行工具时于本机现场编译。</li>
 * </ol>
 *
 * <p>同一个助手还负责 Apple 空间音频渲染：启动参数中带有音频桥端口与令牌时，助手会连接模组并播放声场。
 */
public final class MacHelperLauncher {
	/** 助手 .app 名称。 */
	public static final String APP_NAME = "SpatialAudioHeadTracker.app";

	/** 助手可执行文件名。 */
	public static final String EXECUTABLE_NAME = "SpatialAudioHeadTracker";

	/** jar 内预编译助手的资源根目录。 */
	private static final String BUNDLE_RESOURCE_ROOT = "/spatialaudio/helper/bundle/";

	/** jar 内助手源码的资源目录。 */
	private static final String SOURCE_RESOURCE_ROOT = "/spatialaudio/helper/src/";

	/** 最低支持的 macOS 主版本号（CMHeadphoneMotionManager 在 macOS 14 Sonoma 引入）。 */
	private static final int MIN_MACOS_MAJOR = 14;

	/** 用户自备助手所在的子目录名。 */
	public static final String CUSTOM_DIRECTORY = "custom";

	/**
	 * 启动器状态。
	 */
	public enum State {
		/** 尚未启动。 */
		IDLE,
		/** 正在解压或校验助手。 */
		PREPARING,
		/** 正在本机编译助手。 */
		COMPILING,
		/** 已通过 open 命令启动。 */
		LAUNCHED,
		/** 启动失败。 */
		FAILED,
		/** 既没有预编译助手，也没有可用的编译器。 */
		NOT_FOUND,
		/** 系统版本不受支持。 */
		UNSUPPORTED_OS
	}

	/** 当前状态。 */
	private volatile State state = State.IDLE;

	/** 最近一次错误信息。 */
	private volatile String lastError = "";

	/** 助手进程 PID（由助手的状态包上报）。 */
	private volatile long helperPid = -1L;

	/** 启动时刻（纳秒）。 */
	private volatile long launchedAtNanos;

	/** 启动代次，用于丢弃过期的后台任务。 */
	private volatile int generation;

	/** 当前使用的是否为用户自备的助手。 */
	private volatile boolean customHelper;

	/**
	 * 当前系统是否为 macOS。
	 *
	 * @return 是 macOS 时返回 true
	 */
	public static boolean isMacOs() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}

	/**
	 * 当前 macOS 版本是否支持耳机运动数据（macOS 14 及以上）。
	 *
	 * @return 支持时返回 true
	 */
	public static boolean isSupportedMacOs() {
		if (!isMacOs()) {
			return false;
		}
		final String version = System.getProperty("os.version", "0");
		try {
			final int major = Integer.parseInt(version.split("\\.")[0].trim());
			return major >= MIN_MACOS_MAJOR;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * 当前状态。
	 *
	 * @return 状态
	 */
	public State state() {
		return this.state;
	}

	/**
	 * 最近一次错误信息。
	 *
	 * @return 错误信息，没有时为空字符串
	 */
	public String lastError() {
		return this.lastError;
	}

	/**
	 * 启动以来经过的时间。
	 *
	 * @param nowNanos 当前时刻
	 * @return 秒数；尚未启动时返回 0
	 */
	public double secondsSinceLaunch(final long nowNanos) {
		return this.state == State.LAUNCHED ? (nowNanos - this.launchedAtNanos) / 1.0e9 : 0.0;
	}

	/**
	 * 记录助手上报的进程 PID，用于退出时结束助手。
	 *
	 * @param pid 进程号
	 */
	public void setHelperPid(final long pid) {
		if (pid > 0) {
			this.helperPid = pid;
		}
	}

	/**
	 * 当前使用的是否为用户自备的助手。
	 *
	 * @return 是自备助手时返回 true
	 */
	public boolean customHelper() {
		return this.customHelper;
	}

	/**
	 * 在后台线程准备并启动助手。
	 *
	 * @param port 助手发送头部姿态的目标 UDP 端口；0 表示不需要头部姿态（助手不会读取耳机运动数据）
	 * @param audio Apple 空间音频的音频桥参数；为 null 表示不需要渲染声场
	 */
	public void startAsync(final int port, final AppleRenderer.HelperLink audio) {
		if (!isMacOs()) {
			return;
		}
		if (!isSupportedMacOs()) {
			this.state = State.UNSUPPORTED_OS;
			this.lastError = "macOS " + System.getProperty("os.version");
			return;
		}
		final int myGeneration = ++this.generation;
		this.state = State.PREPARING;
		final Thread thread = new Thread(() -> this.prepareAndLaunch(port, audio, myGeneration), "SpatialAudio-HelperLauncher");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * 停止助手进程。助手本身也会在检测到游戏进程退出后自行结束，这里是额外保障。
	 */
	public void stop() {
		this.generation++;
		final long pid = this.helperPid;
		if (pid > 0) {
			ProcessHandle.of(pid).ifPresent(handle -> {
				if (handle.info().command().map(cmd -> cmd.contains(EXECUTABLE_NAME)).orElse(false)) {
					handle.destroy();
				}
			});
		}
		this.helperPid = -1L;
		if (this.state == State.LAUNCHED || this.state == State.PREPARING || this.state == State.COMPILING) {
			this.state = State.IDLE;
		}
	}

	/**
	 * 后台任务：准备助手文件并启动。
	 *
	 * @param port 姿态目标端口，0 表示不需要
	 * @param audio 音频桥参数，可为 null
	 * @param myGeneration 本次任务代次
	 */
	private void prepareAndLaunch(final int port, final AppleRenderer.HelperLink audio, final int myGeneration) {
		try {
			final Path app = this.prepareBundle();
			if (app == null || myGeneration != this.generation) {
				return;
			}
			this.launch(app, port, audio);
			if (myGeneration == this.generation) {
				this.launchedAtNanos = System.nanoTime();
				this.state = State.LAUNCHED;
				SpatialAudio.LOGGER.info("macOS 助手已启动：姿态端口 {}，音频桥端口 {}{}", port, audio == null ? "-" : audio.port(),
					this.customHelper ? "（用户自备助手）" : "");
			}
		} catch (IOException | RuntimeException e) {
			this.fail("启动 AirPods 头部追踪助手失败：" + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.fail("启动 AirPods 头部追踪助手被中断", e);
		}
	}

	/**
	 * 准备助手 .app：优先解压预编译版本，否则尝试现场编译。
	 *
	 * @return .app 路径；无法准备时返回 null（状态已更新）
	 * @throws IOException 文件操作失败
	 * @throws InterruptedException 等待外部命令时被中断
	 */
	private Path prepareBundle() throws IOException, InterruptedException {
		final Path base = helperDirectory();
		Files.createDirectories(base);
		final Path custom = base.resolve(CUSTOM_DIRECTORY).resolve(APP_NAME);
		this.customHelper = Files.isRegularFile(custom.resolve("Contents/MacOS/" + EXECUTABLE_NAME));
		if (this.customHelper) {
			return custom;
		}
		final Path app = base.resolve(APP_NAME);

		final List<String> manifest = readResourceLines(BUNDLE_RESOURCE_ROOT + "manifest.txt");
		if (!manifest.isEmpty() && manifest.getFirst().startsWith("version=")) {
			final String version = manifest.getFirst().substring("version=".length()).trim();
			final Path marker = base.resolve("installed-version.txt");
			if (Files.isDirectory(app) && version.equals(readString(marker))) {
				return app;
			}
			deleteRecursively(app);
			for (final String relative : manifest.subList(1, manifest.size())) {
				final String trimmed = relative.trim();
				if (trimmed.isEmpty() || trimmed.contains("..")) {
					continue;
				}
				final Path target = base.resolve(trimmed);
				Files.createDirectories(target.getParent());
				try (InputStream in = MacHelperLauncher.class.getResourceAsStream(BUNDLE_RESOURCE_ROOT + trimmed)) {
					if (in == null) {
						throw new IOException("jar 中缺少助手文件：" + trimmed);
					}
					Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			markExecutable(app);
			postProcess(app);
			Files.writeString(marker, version, StandardCharsets.UTF_8);
			return app;
		}

		return this.compileFromSource(base, app);
	}

	/**
	 * 使用 Xcode 命令行工具把 jar 内附带的 Swift 源码编译成助手 .app。
	 *
	 * @param base 助手目录
	 * @param app .app 路径
	 * @return .app 路径；无法编译时返回 null
	 * @throws IOException 文件操作失败
	 * @throws InterruptedException 等待编译时被中断
	 */
	private Path compileFromSource(final Path base, final Path app) throws IOException, InterruptedException {
		final byte[] swift = readResourceBytes(SOURCE_RESOURCE_ROOT + "main.swift");
		final byte[] plist = readResourceBytes(SOURCE_RESOURCE_ROOT + "Info.plist");
		if (swift == null || plist == null) {
			this.state = State.NOT_FOUND;
			this.lastError = "jar 中缺少助手源码";
			return null;
		}
		final String sourceHash = sha256(swift, plist);
		final Path marker = base.resolve("compiled-version.txt");
		if (Files.isRegularFile(app.resolve("Contents/MacOS/" + EXECUTABLE_NAME)) && sourceHash.equals(readString(marker))) {
			return app;
		}
		final CommandResult probe = run(List.of("/usr/bin/xcrun", "--find", "swiftc"), 30);
		if (probe.exitCode() != 0) {
			this.state = State.NOT_FOUND;
			this.lastError = "未找到 Swift 编译器，请执行 xcode-select --install 安装 Xcode 命令行工具";
			SpatialAudio.LOGGER.warn("无法编译 AirPods 头部追踪助手：{}", this.lastError);
			return null;
		}

		this.state = State.COMPILING;
		SpatialAudio.LOGGER.info("正在本机编译 AirPods 头部追踪助手（首次使用约需 10~60 秒）……");
		final Path srcDir = base.resolve("src");
		Files.createDirectories(srcDir);
		final Path swiftFile = srcDir.resolve("main.swift");
		final Path plistFile = srcDir.resolve("Info.plist");
		Files.write(swiftFile, swift);
		Files.write(plistFile, plist);

		deleteRecursively(app);
		final Path macOsDir = app.resolve("Contents/MacOS");
		Files.createDirectories(macOsDir);
		final String arch = hostArchitecture();
		final CommandResult compile = run(List.of(
			"/usr/bin/xcrun", "swiftc", "-O",
			"-target", arch + "-apple-macos14.0",
			"-o", macOsDir.resolve(EXECUTABLE_NAME).toString(),
			swiftFile.toString(),
			"-Xlinker", "-sectcreate", "-Xlinker", "__TEXT", "-Xlinker", "__info_plist", "-Xlinker", plistFile.toString()
		), 600);
		if (compile.exitCode() != 0) {
			this.state = State.FAILED;
			this.lastError = "Swift 编译失败：" + compile.output();
			SpatialAudio.LOGGER.error("编译 AirPods 头部追踪助手失败：\n{}", compile.output());
			return null;
		}
		Files.write(app.resolve("Contents/Info.plist"), plist);
		Files.writeString(app.resolve("Contents/PkgInfo"), "APPL????", StandardCharsets.US_ASCII);
		markExecutable(app);
		postProcess(app);
		Files.writeString(marker, sourceHash, StandardCharsets.UTF_8);
		SpatialAudio.LOGGER.info("AirPods 头部追踪助手编译完成：{}", app);
		return app;
	}

	/**
	 * 通过 {@code open} 启动助手，使其成为独立的应用进程。
	 *
	 * @param app .app 路径
	 * @param port 姿态目标端口，0 表示不需要
	 * @param audio 音频桥参数，可为 null
	 * @throws IOException 启动失败
	 * @throws InterruptedException 等待时被中断
	 */
	private void launch(final Path app, final int port, final AppleRenderer.HelperLink audio) throws IOException, InterruptedException {
		final List<String> command = new ArrayList<>(List.of(
			"/usr/bin/open", "-n", "-g", "-j", "-a", app.toString(),
			"--args",
			"--port", Integer.toString(port),
			"--parent-pid", Long.toString(ProcessHandle.current().pid())
		));
		if (audio != null) {
			command.add("--audio-port");
			command.add(Integer.toString(audio.port()));
			command.add("--audio-token");
			command.add(audio.token());
		}
		final CommandResult result = run(command, 20);
		if (result.exitCode() != 0) {
			throw new IOException("open 返回 " + result.exitCode() + "：" + result.output());
		}
	}

	/**
	 * 记录失败状态。
	 *
	 * @param message 错误信息
	 * @param error 异常
	 */
	private void fail(final String message, final Throwable error) {
		this.state = State.FAILED;
		this.lastError = message;
		SpatialAudio.LOGGER.error(message, error);
	}

	/**
	 * 助手存放目录：{@code .minecraft/config/spatialaudio/helper}。
	 *
	 * @return 目录路径
	 */
	public static Path helperDirectory() {
		return FabricLoader.getInstance().getConfigDir().resolve(SpatialAudio.MOD_ID).resolve("helper");
	}

	/**
	 * 去除隔离属性并做 ad-hoc 代码签名，确保 Apple Silicon 与 Gatekeeper 允许运行。
	 *
	 * @param app .app 路径
	 * @throws InterruptedException 等待时被中断
	 */
	private static void postProcess(final Path app) throws InterruptedException {
		try {
			run(List.of("/usr/bin/xattr", "-dr", "com.apple.quarantine", app.toString()), 30);
		} catch (IOException e) {
			SpatialAudio.LOGGER.debug("移除隔离属性失败（可忽略）：{}", e.toString());
		}
		try {
			final CommandResult sign = run(List.of("/usr/bin/codesign", "--force", "--deep", "--sign", "-", app.toString()), 60);
			if (sign.exitCode() != 0) {
				SpatialAudio.LOGGER.warn("助手 ad-hoc 签名失败：{}", sign.output());
			}
		} catch (IOException e) {
			SpatialAudio.LOGGER.warn("无法执行 codesign：{}", e.toString());
		}
	}

	/**
	 * 给 Contents/MacOS 下的文件加上可执行权限（jar 解压不会保留权限位）。
	 *
	 * @param app .app 路径
	 * @throws IOException 文件操作失败
	 */
	private static void markExecutable(final Path app) throws IOException {
		final Path macOsDir = app.resolve("Contents/MacOS");
		if (!Files.isDirectory(macOsDir)) {
			return;
		}
		try (Stream<Path> files = Files.list(macOsDir)) {
			files.forEach(file -> {
				if (!file.toFile().setExecutable(true, false)) {
					SpatialAudio.LOGGER.warn("无法设置可执行权限：{}", file);
				}
			});
		}
	}

	/**
	 * 判断主机 CPU 架构对应的 Swift 目标名。
	 *
	 * @return arm64 或 x86_64
	 */
	private static String hostArchitecture() {
		final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return arch.contains("aarch64") || arch.contains("arm") ? "arm64" : "x86_64";
	}

	/**
	 * 外部命令执行结果。
	 *
	 * @param exitCode 退出码
	 * @param output 合并后的标准输出与错误输出
	 */
	private record CommandResult(int exitCode, String output) {
	}

	/**
	 * 执行外部命令并等待结束。
	 *
	 * @param command 命令与参数
	 * @param timeoutSeconds 超时（秒）
	 * @return 执行结果；超时视为失败
	 * @throws IOException 无法启动进程
	 * @throws InterruptedException 等待时被中断
	 */
	private static CommandResult run(final List<String> command, final long timeoutSeconds) throws IOException, InterruptedException {
		final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		final StringBuilder output = new StringBuilder();
		final Thread reader = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = in.readLine()) != null) {
					synchronized (output) {
						if (output.length() < 8000) {
							output.append(line).append('\n');
						}
					}
				}
			} catch (IOException ignored) {
				// 进程结束后流被关闭属于正常情况。
			}
		}, "SpatialAudio-ProcessReader");
		reader.setDaemon(true);
		reader.start();
		if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			return new CommandResult(-1, "命令超时：" + String.join(" ", command));
		}
		reader.join(2000L);
		synchronized (output) {
			return new CommandResult(process.exitValue(), output.toString().trim());
		}
	}

	/**
	 * 读取类路径资源的全部行。
	 *
	 * @param resource 资源路径
	 * @return 行列表；资源不存在时返回空列表
	 * @throws IOException 读取失败
	 */
	private static List<String> readResourceLines(final String resource) throws IOException {
		final List<String> lines = new ArrayList<>();
		try (InputStream in = MacHelperLauncher.class.getResourceAsStream(resource)) {
			if (in == null) {
				return lines;
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					lines.add(line);
				}
			}
		}
		return lines;
	}

	/**
	 * 读取类路径资源的全部字节。
	 *
	 * @param resource 资源路径
	 * @return 字节数组；资源不存在时返回 null
	 * @throws IOException 读取失败
	 */
	private static byte[] readResourceBytes(final String resource) throws IOException {
		try (InputStream in = MacHelperLauncher.class.getResourceAsStream(resource)) {
			return in == null ? null : in.readAllBytes();
		}
	}

	/**
	 * 读取文本文件，不存在时返回空字符串。
	 *
	 * @param file 文件
	 * @return 去除首尾空白的内容
	 */
	private static String readString(final Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * 计算若干字节数组的 SHA-256 摘要。
	 *
	 * @param parts 数据
	 * @return 十六进制摘要
	 */
	private static String sha256(final byte[]... parts) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (final byte[] part : parts) {
				digest.update(part);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 递归删除目录。
	 *
	 * @param root 根目录
	 * @throws IOException 删除失败
	 */
	private static void deleteRecursively(final Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (final Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
