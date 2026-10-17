package io.github.obsidianplusplus.spatialaudio.gametest;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.acoustics.AcousticsManager;
import io.github.obsidianplusplus.spatialaudio.acoustics.EnvironmentSnapshot;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.audio.SpatialSource;
import io.github.obsidianplusplus.spatialaudio.client.screen.CategorySettingsScreen;
import io.github.obsidianplusplus.spatialaudio.client.screen.SpatialAudioConfigScreen;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import io.github.obsidianplusplus.spatialaudio.headtracking.OpenTrackProtocol;
import io.github.obsidianplusplus.spatialaudio.headtracking.TrackerStatus;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.sounds.SoundSource;

/**
 * 客户端集成测试：在真实的 Minecraft 26.3 客户端中验证空间音频的完整链路。
 *
 * <p>测试内容：
 * <ol>
 *   <li>OpenAL 初始化后 HRTF 与 EFX 均已启用（注入点全部生效）；</li>
 *   <li>双耳方位：左侧声源的左声道能量明显更大；正前方声源左右平衡；</li>
 *   <li>头部追踪：通过 OpenTrack UDP 注入“向左转头 90°”后，正前方的声源移到右耳，向右转头则移到左耳；</li>
 *   <li>声学：墙后的声源被遮挡变闷，封闭石室内产生混响，水下启用介质滤波；</li>
 *   <li>界面：设置界面、类别界面与调试叠加层在英文与简体中文下都能正常打开并截图。</li>
 * </ol>
 * 当环境变量 {@code SPATIALAUDIO_TEST_WAV} 指向 OpenAL Soft wave 后端的输出文件时，
 * 会直接分析实际输出的双耳音频；否则跳过音频能量断言。
 */
public final class SpatialAudioClientGameTest implements FabricClientGameTest {
	/** 测试平台高度。 */
	private static final int Y = 200;

	/** 听者眼睛高度。 */
	private static final double EYE = Y + 1.62;

	/** OpenTrack 模拟发送的偏航（角度，OpenTrack 约定右转为正）。 */
	private volatile double fakeYaw;

	/** 是否继续发送模拟数据。 */
	private volatile boolean sending;

	@Override
	public void runTest(final ClientGameTestContext context) {
		context.waitFor(minecraft -> AudioSystem.get().isAlive(), 600);
		final AudioSystem audio = AudioSystem.get();
		log("设备：%s，HRTF：%s（%s），EFX：%s，发送通道：%d，立体声定位：%s，立体声角度：%s",
			audio.deviceName(), audio.hrtf().enabled(), audio.hrtf().name(), audio.capabilities().efx(),
			audio.capabilities().auxSends(), audio.capabilities().sourceSpatialize(), audio.capabilities().stereoAngles());
		check(audio.hrtf().enabled(), "HRTF 应已开启");
		check(audio.capabilities().efx() && audio.capabilities().auxSends() >= 2, "EFX 与两个辅助发送通道应可用");
		context.takeScreenshot("spatialaudio-title");

		final SpatialAudioConfig withHud = ConfigManager.snapshot();
		withHud.showHud = true;
		ConfigManager.replace(withHud);

		try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(state -> {
			state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			state.getNormalPresetList().stream()
				.filter(entry -> entry.preset() != null
					&& entry.preset().unwrapKey().map(key -> key.identifier().getPath().equals("flat")).orElse(false))
				.findFirst()
				.ifPresent(state::setWorldType);
		}).create()) {
			world.getConnection().waitForChunksRender();
			final TestServerContext server = world.getServer();
			server.runCommand("time set day");
			server.runCommand("weather clear");
			server.runCommand(String.format(Locale.ROOT, "fill -12 %d -12 12 %d 12 minecraft:smooth_stone", Y - 1, Y - 1));
			server.runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d 0.5 0 0", Y));
			context.waitTicks(40);

			this.testBinauralDirection(context, server);
			this.testHeadTracking(context, server);
			this.testOcclusion(context, server);
			this.testEnvironment(context, server);
			this.testMedium(context, server);

			server.runCommand("stopsound @a");
			context.takeScreenshot("spatialaudio-hud");
			context.setScreen(() -> new SpatialAudioConfigScreen(null));
			context.waitTicks(10);
			context.takeScreenshot("spatialaudio-config");
			context.setScreen(() -> new CategorySettingsScreen(null, ConfigManager.snapshot()));
			context.waitTicks(10);
			context.takeScreenshot("spatialaudio-categories");
			context.setScreen(() -> null);
			context.waitTicks(5);
			this.testChineseInterface(context);
		} finally {
			this.sending = false;
		}
		log("全部集成测试通过");
	}

	/**
	 * 双耳方位测试：面朝南（+z）时，东侧（+x）是左手边。
	 *
	 * @param context 测试上下文
	 * @param server 服务端上下文
	 */
	private void testBinauralDirection(final ClientGameTestContext context, final TestServerContext server) {
		playRecord(server, 8.5, EYE, 0.5);
		context.waitTicks(30);
		final double[] left = measure(context, 40);
		if (left != null) {
			log("左侧声源：L=%.5f R=%.5f", left[0], left[1]);
			check(left[0] > left[1] * 1.5, "左侧声源的左声道应明显更响");
		}
		server.runCommand("stopsound @a record");
		playRecord(server, 0.5, EYE, 8.5);
		context.waitTicks(30);
		final double[] front = measure(context, 40);
		if (front != null) {
			log("正前方声源：L=%.5f R=%.5f", front[0], front[1]);
			final double ratio = front[0] / Math.max(1.0e-9, front[1]);
			check(ratio > 0.7 && ratio < 1.43, "正前方声源左右应基本平衡，比值 " + ratio);
		}
		final long sources = AudioSystem.get().sources().all().stream().filter(s -> s.category == SoundSource.RECORDS).count();
		check(sources >= 1, "应登记到唱片声源");
	}

	/**
	 * 头部追踪测试：模拟 OpenTrack 数据，验证转头后声源换耳。
	 *
	 * @param context 测试上下文
	 * @param server 服务端上下文
	 */
	private void testHeadTracking(final ClientGameTestContext context, final TestServerContext server) {
		final int port = HeadTrackingManager.get().boundPort();
		check(port > 0, "UDP 接收器应已启动");
		this.fakeYaw = 0.0;
		this.sending = true;
		final Thread sender = new Thread(() -> this.sendOpenTrack(port), "SpatialAudio-Test-OpenTrack");
		sender.setDaemon(true);
		sender.start();
		context.waitFor(minecraft -> HeadTrackingManager.get().status() == TrackerStatus.ACTIVE_OPENTRACK, 200);
		context.waitTicks(20);
		HeadTrackingManager.get().recenter();
		context.waitTicks(10);

		// 向左转头 90°（OpenTrack 偏航 -90），正前方的声源应移到右耳。
		this.fakeYaw = -90.0;
		context.waitTicks(30);
		final double headYaw = Math.toDegrees(AudioSystem.get().listener().head().toEulerZXY()[0]);
		log("向左转头后的听者偏航：%.1f°", headYaw);
		check(headYaw > 70.0 && headYaw < 110.0, "听者偏航应约为 +90°，实际 " + headYaw);
		context.takeScreenshot("spatialaudio-headtracking");
		final double[] turnedLeft = measure(context, 40);
		if (turnedLeft != null) {
			log("向左转头：L=%.5f R=%.5f", turnedLeft[0], turnedLeft[1]);
			check(turnedLeft[1] > turnedLeft[0] * 1.5, "向左转头后正前方声源应在右耳");
		}

		// 向右转头 90°，正前方的声源应移到左耳。
		this.fakeYaw = 90.0;
		context.waitTicks(30);
		final double[] turnedRight = measure(context, 40);
		if (turnedRight != null) {
			log("向右转头：L=%.5f R=%.5f", turnedRight[0], turnedRight[1]);
			check(turnedRight[0] > turnedRight[1] * 1.5, "向右转头后正前方声源应在左耳");
		}
		this.fakeYaw = 0.0;
		context.waitTicks(20);
		server.runCommand("stopsound @a record");
	}

	/**
	 * 遮挡测试：在听者与声源之间砌一堵石墙。
	 *
	 * @param context 测试上下文
	 * @param server 服务端上下文
	 */
	private void testOcclusion(final ClientGameTestContext context, final TestServerContext server) {
		server.runCommand(String.format(Locale.ROOT, "fill 4 %d -3 4 %d 3 minecraft:stone", Y, Y + 5));
		context.waitTicks(10);
		playRecord(server, 8.5, EYE, 0.5);
		context.waitTicks(30);
		final SpatialSource record = AudioSystem.get().sources().all().stream()
			.filter(s -> s.category == SoundSource.RECORDS && s.active)
			.findFirst()
			.orElse(null);
		check(record != null, "应找到唱片声源");
		log("墙后声源：直达增益 %.3f，高频 %.3f，重定向 %.3f", record.target.directGain(), record.target.directGainHF(),
			record.target.redirectBlend());
		check(record.target.directGainHF() < 0.8f, "墙后声源应被遮挡变闷");
		server.runCommand("stopsound @a record");
		server.runCommand(String.format(Locale.ROOT, "fill 4 %d -3 4 %d 3 minecraft:air", Y, Y + 5));
	}

	/**
	 * 环境测试：用石头把听者封闭在小房间里，检查混响。
	 *
	 * @param context 测试上下文
	 * @param server 服务端上下文
	 */
	private void testEnvironment(final ClientGameTestContext context, final TestServerContext server) {
		final EnvironmentSnapshot open = AcousticsManager.get().environment();
		log("开阔平台：封闭度 %.2f，RT60 %.2f 秒，天空 %.2f", open.enclosure(), open.rt60(), open.skyOpenness());
		server.runCommand(String.format(Locale.ROOT, "fill -4 %d -4 4 %d 4 minecraft:stone hollow", Y - 1, Y + 5));
		context.waitTicks(40);
		final EnvironmentSnapshot room = AcousticsManager.get().environment();
		log("石室：封闭度 %.2f，RT60 %.2f 秒，混响衰减 %.2f 秒", room.enclosure(), room.rt60(),
			AudioSystem.get().global().reverb().decayTime());
		check(room.enclosure() > 0.9f, "石室封闭度应接近 1");
		check(room.rt60() > open.rt60(), "石室混响应长于开阔地");
		check(AudioSystem.get().global().reverb().gain() > 0.0f, "石室应产生混响");
		context.takeScreenshot("spatialaudio-room");
		server.runCommand(String.format(Locale.ROOT, "fill -4 %d -4 4 %d 4 minecraft:air", Y, Y + 5));
	}

	/**
	 * 介质测试：把听者头部所在方块换成水。
	 *
	 * @param context 测试上下文
	 * @param server 服务端上下文
	 */
	private void testMedium(final ClientGameTestContext context, final TestServerContext server) {
		server.runCommand(String.format(Locale.ROOT, "setblock 0 %d 0 minecraft:water", Y + 1));
		context.waitTicks(20);
		log("水下：介质高频增益 %.2f", AudioSystem.get().global().mediumGainHF());
		check(AudioSystem.get().global().mediumGainHF() < 0.5f, "水下应启用介质滤波");
		server.runCommand(String.format(Locale.ROOT, "setblock 0 %d 0 minecraft:air", Y + 1));
		context.waitTicks(20);
	}

	/**
	 * 切换到简体中文，截取设置界面与叠加层，检查中文文本是否完整显示。
	 *
	 * @param context 测试上下文
	 */
	private void testChineseInterface(final ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			minecraft.options.languageCode = "zh_cn";
			minecraft.getLanguageManager().setSelected("zh_cn");
			minecraft.reloadResourcePacks();
		});
		context.waitTicks(5);
		context.waitFor(minecraft -> minecraft.gui.overlay() == null, 2400);
		context.waitTicks(10);
		context.takeScreenshot("spatialaudio-hud-zh");
		context.setScreen(() -> new SpatialAudioConfigScreen(null));
		context.waitTicks(10);
		context.takeScreenshot("spatialaudio-config-zh");
		// 逐页向下滚动，检查长列表中每个选项的中文文本都能完整显示。
		context.getInput().setCursorPos(427.0, 240.0);
		for (int page = 1; page <= 6; page++) {
			context.getInput().scroll(-10.0);
			context.waitTicks(5);
			context.takeScreenshot("spatialaudio-config-zh-" + page);
		}
		context.setScreen(() -> new CategorySettingsScreen(null, ConfigManager.snapshot()));
		context.waitTicks(10);
		context.takeScreenshot("spatialaudio-categories-zh");
		context.setScreen(() -> null);
		context.waitTicks(5);
	}

	/**
	 * 在指定位置播放一张唱片（持续且响亮，便于测量）。
	 *
	 * @param server 服务端上下文
	 * @param x 坐标 x
	 * @param y 坐标 y
	 * @param z 坐标 z
	 */
	private static void playRecord(final TestServerContext server,
			final double x, final double y, final double z) {
		server.runCommand(String.format(Locale.ROOT, "playsound minecraft:music_disc.cat record @a %.2f %.2f %.2f 4 1 1", x, y, z));
	}

	/**
	 * 以 60 Hz 发送 OpenTrack 数据包。
	 *
	 * @param port 目标端口
	 */
	private void sendOpenTrack(final int port) {
		try (DatagramSocket socket = new DatagramSocket()) {
			final InetAddress loopback = InetAddress.getLoopbackAddress();
			while (this.sending) {
				final byte[] data = OpenTrackProtocol.encode(0.0, 0.0, 0.0, this.fakeYaw, 0.0, 0.0);
				socket.send(new DatagramPacket(data, data.length, loopback, port));
				Thread.sleep(16L);
			}
		} catch (IOException | InterruptedException e) {
			SpatialAudio.LOGGER.error("模拟 OpenTrack 发送失败", e);
		}
	}

	/**
	 * 测量接下来若干刻内 OpenAL 输出的左右声道 RMS。
	 *
	 * @param context 测试上下文
	 * @param ticks 测量时长（刻）
	 * @return {左 RMS, 右 RMS}；未配置 WAV 输出时返回 null
	 */
	private static double[] measure(final ClientGameTestContext context, final int ticks) {
		final String env = System.getenv("SPATIALAUDIO_TEST_WAV");
		if (env == null || env.isBlank()) {
			context.waitTicks(ticks);
			return null;
		}
		final Path wav = Path.of(env);
		try {
			final WavFormat format = WavFormat.read(wav);
			final long start = Files.size(wav);
			context.waitTicks(ticks);
			final long end = Files.size(wav);
			return format.rms(wav, start, end);
		} catch (IOException e) {
			throw new AssertionError("读取 WAV 输出失败：" + wav, e);
		}
	}

	/**
	 * 断言。
	 *
	 * @param condition 条件
	 * @param message 失败信息
	 */
	private static void check(final boolean condition, final String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	/**
	 * 输出测试日志。
	 *
	 * @param format 格式
	 * @param args 参数
	 */
	private static void log(final String format, final Object... args) {
		SpatialAudio.LOGGER.info("[集成测试] " + String.format(Locale.ROOT, format, args));
	}

	/**
	 * WAV 文件格式（只支持 OpenAL Soft wave 后端写出的 16 位整数或 32 位浮点立体声）。
	 *
	 * @param dataOffset 数据起始偏移
	 * @param channels 声道数
	 * @param bits 位深
	 * @param isFloat 是否为浮点样本
	 */
	private record WavFormat(long dataOffset, int channels, int bits, boolean isFloat) {
		/**
		 * 解析 WAV 头。
		 *
		 * @param wav 文件
		 * @return 格式
		 * @throws IOException 读取失败
		 */
		static WavFormat read(final Path wav) throws IOException {
			try (RandomAccessFile file = new RandomAccessFile(wav.toFile(), "r")) {
				final byte[] header = new byte[(int) Math.min(512, file.length())];
				file.readFully(header);
				final ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
				int offset = 12;
				int channels = 2;
				int bits = 16;
				boolean isFloat = false;
				while (offset + 8 <= header.length) {
					final String id = new String(header, offset, 4, StandardCharsets.US_ASCII);
					final int size = b.getInt(offset + 4);
					if (id.equals("fmt ")) {
						final int tag = b.getShort(offset + 8) & 0xFFFF;
						channels = b.getShort(offset + 10);
						bits = b.getShort(offset + 22);
						isFloat = tag == 3 || (tag == 0xFFFE && (b.getShort(offset + 32) & 0xFFFF) == 3);
					} else if (id.equals("data")) {
						return new WavFormat(offset + 8L, channels, bits, isFloat);
					}
					offset += 8 + size + (size & 1);
				}
				throw new IOException("找不到 data 块");
			}
		}

		/**
		 * 计算文件区间内左右声道的 RMS。
		 *
		 * @param wav 文件
		 * @param from 起始字节
		 * @param to 结束字节
		 * @return {左 RMS, 右 RMS}
		 * @throws IOException 读取失败
		 */
		double[] rms(final Path wav, final long from, final long to) throws IOException {
			final int frame = this.channels * this.bits / 8;
			final long start = Math.max(this.dataOffset, this.dataOffset + ((from - this.dataOffset) / frame) * frame);
			final long frames = Math.max(0L, (to - start) / frame);
			check(frames > 4800, "WAV 输出数据不足（" + frames + " 帧），wave 后端可能没有工作");
			final byte[] data = new byte[(int) (frames * frame)];
			try (RandomAccessFile file = new RandomAccessFile(wav.toFile(), "r")) {
				file.seek(start);
				file.readFully(data);
			}
			final ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
			double left = 0.0;
			double right = 0.0;
			for (long i = 0; i < frames; i++) {
				final int base = (int) (i * frame);
				final double l = this.sample(b, base);
				final double r = this.sample(b, base + this.bits / 8);
				left += l * l;
				right += r * r;
			}
			return new double[] {Math.sqrt(left / frames), Math.sqrt(right / frames)};
		}

		/**
		 * 读取一个样本并归一化到 [-1, 1]。
		 *
		 * @param b 缓冲区
		 * @param position 字节位置
		 * @return 样本值
		 */
		private double sample(final ByteBuffer b, final int position) {
			if (this.isFloat) {
				return b.getFloat(position);
			}
			return this.bits == 16 ? b.getShort(position) / 32768.0 : b.getInt(position) / 2147483648.0;
		}
	}
}
