package io.github.obsidianplusplus.spatialaudio.gametest;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.acoustics.AcousticsManager;
import io.github.obsidianplusplus.spatialaudio.acoustics.EnvironmentSnapshot;
import io.github.obsidianplusplus.spatialaudio.apple.AppleBridgeProtocol;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRenderer;
import io.github.obsidianplusplus.spatialaudio.apple.AppleRendererStatus;
import io.github.obsidianplusplus.spatialaudio.audio.AudioSystem;
import io.github.obsidianplusplus.spatialaudio.audio.SpatialSource;
import io.github.obsidianplusplus.spatialaudio.client.SpatialAudioClient;
import io.github.obsidianplusplus.spatialaudio.client.screen.CategorySettingsScreen;
import io.github.obsidianplusplus.spatialaudio.client.screen.SpatialAudioConfigScreen;
import io.github.obsidianplusplus.spatialaudio.config.AppleBedFormat;
import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.config.SpatialRenderer;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import io.github.obsidianplusplus.spatialaudio.headtracking.OpenTrackProtocol;
import io.github.obsidianplusplus.spatialaudio.headtracking.TrackerStatus;
import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
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
 *   <li>界面：设置界面、类别界面与调试叠加层在英文与简体中文下都能正常打开并截图；</li>
 *   <li>Apple 渲染方式：在 Linux 上放行回环设备，由测试线程扮演 macOS 助手接收 SAAB 声场，
 *       验证 Ambisonics 声场的方向、头部姿态只交给助手而不旋转 OpenAL 听者、系统空间化立体声模式与恢复内置渲染器。</li>
 * </ol>
 * 当环境变量 {@code SPATIALAUDIO_TEST_WAV} 指向 OpenAL Soft wave 后端的输出文件时，
 * 会直接分析实际输出的双耳音频；否则跳过音频能量断言（Apple 声场的分析不依赖该文件）。
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

	/** OpenTrack 数据的目标 UDP 端口（切换渲染方式会重启头部追踪，端口可能变化）。 */
	private volatile int openTrackPort;

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
			this.testAppleRenderer(context, server);
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
		this.openTrackPort = port;
		this.sending = true;
		final Thread sender = new Thread(this::sendOpenTrack, "SpatialAudio-Test-OpenTrack");
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
	 * Apple 渲染方式测试：在 Linux 上放行回环设备，由测试线程扮演 macOS 助手。
	 *
	 * <p>面朝南（+z）时东侧（+x）是左手边。Ambisonics 采用 ACN 顺序、SN3D 归一化：
	 * 声道 0 为 W（全向），1 为 Y（左为正），3 为 X（前为正）。来自方向（方位角 θ，左为正；仰角 φ）的平面波满足
	 * Y = W·sinθ·cosφ、X = W·cosθ·cosφ，因此左侧声源的 W 与 Y 强正相关且 Y/W 增益接近 1、X/W 增益接近 0，正前方声源反之。
	 * 相关系数与幅度无关（声源稍偏一点，微弱的 X 也会与 W 完全相关），所以“基本为零”的判断使用增益（回归系数）而不是相关系数。
	 * 头部旋转只通过 HEAD 消息交给助手，OpenAL 渲染的声场始终以摄像机为参照。
	 *
	 * @param context 测试上下文
	 * @param server 服务端上下文
	 */
	private void testAppleRenderer(final ClientGameTestContext context, final TestServerContext server) {
		System.setProperty(AppleRenderer.ALLOW_NON_MAC_PROPERTY, "true");
		final FakeAppleHelper helper = new FakeAppleHelper();
		final Thread helperThread = new Thread(helper, "SpatialAudio-Test-AppleHelper");
		helperThread.setDaemon(true);
		helperThread.start();
		try {
			// 切换渲染方式会重启头部追踪；转头角度先归零，避免重新连接时的自动回中把转头当作正前方。
			this.fakeYaw = 0.0;
			server.runCommand("stopsound @a");
			switchRenderer(context, SpatialRenderer.APPLE, AppleBedFormat.AMBISONICS_3);
			context.waitFor(minecraft -> AppleRenderer.get().active() && AudioSystem.get().isAlive(), 1200);
			final AudioSystem audio = AudioSystem.get();
			log("Apple 渲染方式：设备「%s」，渲染方式 %s，HRTF 状态 %s", audio.deviceName(), audio.activeRenderer(), audio.hrtf().statusKey());
			check(audio.activeRenderer() == SpatialRenderer.APPLE, "当前渲染方式应为 APPLE，实际 " + audio.activeRenderer());
			check("spatialaudio.hrtf_status.apple".equals(audio.hrtf().statusKey()), "HRTF 状态应显示由 Apple 渲染");
			check(AppleRenderer.get().format() == AppleBedFormat.AMBISONICS_3, "声场格式应为 3 阶 Ambisonics");

			context.waitFor(minecraft -> helper.streamConfig() != null, 1200);
			final AppleBridgeProtocol.Config config = helper.streamConfig();
			log("助手收到 CONFIG：代次 %d，%d Hz，%d 声道，布局 %d，目标缓冲 %d 帧", config.generation(), config.sampleRate(),
				config.channels(), config.layout(), config.targetFrames());
			check(config.channels() == 16, "3 阶 Ambisonics 应为 16 声道，实际 " + config.channels());
			check(config.layout() == AppleBridgeProtocol.LAYOUT_HOA_ACN_SN3D, "布局应为 HOA ACN/SN3D，实际 " + config.layout());
			check(config.sampleRate() == 48000, "采样率应为 48000，实际 " + config.sampleRate());
			context.waitFor(minecraft -> AppleRenderer.get().status(ConfigManager.get()) == AppleRendererStatus.ACTIVE, 1200);

			// 头部追踪重启后重新对准 OpenTrack 端口并回中。
			this.openTrackPort = HeadTrackingManager.get().boundPort();
			context.waitFor(minecraft -> HeadTrackingManager.get().status() == TrackerStatus.ACTIVE_OPENTRACK, 400);
			context.waitTicks(20);
			HeadTrackingManager.get().recenter();
			context.waitTicks(10);

			// 左侧声源：Y 与 W 强正相关，X 分量接近 0。
			playRecord(server, 8.5, EYE, 0.5);
			context.waitTicks(30);
			final AmbisonicStats left = helper.measure(context, 40);
			log("Apple 左侧声源：%s", left);
			left.checkEnough();
			check(left.correlation(1) > 0.5 && left.gain(1) > 0.5, "左侧声源的 Y 应与 W 强正相关，实际 " + left);
			check(Math.abs(left.gain(3)) < 0.3, "左侧声源的 X 分量应接近 0，实际 " + left);
			server.runCommand("stopsound @a record");

			// 正前方声源：X 与 W 强正相关，Y 分量接近 0。
			playRecord(server, 0.5, EYE, 8.5);
			context.waitTicks(30);
			final AmbisonicStats front = helper.measure(context, 40);
			log("Apple 正前方声源：%s", front);
			front.checkEnough();
			check(front.correlation(3) > 0.5 && front.gain(3) > 0.5, "正前方声源的 X 应与 W 强正相关，实际 " + front);
			check(Math.abs(front.gain(1)) < 0.3, "正前方声源的 Y 分量应接近 0，实际 " + front);

			// 向左转头 90°（OpenTrack 偏航 -90）：助手收到偏航约 +90° 的姿态，OpenAL 听者保持不动，正前方声源仍在 +X。
			this.fakeYaw = -90.0;
			context.waitTicks(30);
			final Quat head = helper.lastHead();
			check(head != null, "助手应收到有效的头部姿态");
			final double helperYaw = Math.toDegrees(head.toEulerZXY()[0]);
			final double listenerYaw = Math.toDegrees(AudioSystem.get().listener().head().toEulerZXY()[0]);
			log("向左转头：助手收到的偏航 %.1f°，OpenAL 听者偏航 %.1f°，HEAD 消息 %d 条", helperYaw, listenerYaw, helper.headCount());
			check(helperYaw > 75.0 && helperYaw < 105.0, "助手收到的偏航应约为 +90°，实际 " + helperYaw);
			check(Math.abs(listenerYaw) < 5.0, "Apple 渲染方式下 OpenAL 听者不应随头部旋转，实际偏航 " + listenerYaw);
			final AmbisonicStats turned = helper.measure(context, 40);
			log("Apple 向左转头后的正前方声源：%s", turned);
			turned.checkEnough();
			check(turned.correlation(3) > 0.5 && turned.gain(3) > 0.5, "转头后正前方声源仍应在声场的 +X 方向，实际 " + turned);
			check(Math.abs(turned.gain(1)) < 0.3, "转头后正前方声源不应移到声场的左右两侧，实际 " + turned);
			this.fakeYaw = 0.0;
			context.waitTicks(20);
			server.runCommand("stopsound @a record");

			// 界面截图：滚动到渲染方式与 Apple 空间音频分组。
			context.setScreen(() -> new SpatialAudioConfigScreen(null));
			context.waitTicks(10);
			context.runOnClient(minecraft -> scrollToRendererOption(minecraft.gui.screen()));
			context.waitTicks(5);
			context.takeScreenshot("spatialaudio-apple-config-zh");
			context.setScreen(() -> null);
			context.waitTicks(5);

			// Apple 系统空间化立体声：普通立体声输出、关闭 HRTF 与本模组的头部追踪，音频桥关闭。
			switchRenderer(context, SpatialRenderer.APPLE_SYSTEM_STEREO, AppleBedFormat.AMBISONICS_3);
			context.waitFor(minecraft -> AudioSystem.get().isAlive()
				&& AudioSystem.get().activeRenderer() == SpatialRenderer.APPLE_SYSTEM_STEREO, 1200);
			log("系统空间化立体声：设备「%s」，HRTF 状态 %s", AudioSystem.get().deviceName(), AudioSystem.get().hrtf().statusKey());
			check("spatialaudio.hrtf_status.system_stereo".equals(AudioSystem.get().hrtf().statusKey()),
				"HRTF 状态应显示由 macOS 空间化立体声处理，实际 " + AudioSystem.get().hrtf().statusKey());
			check(!AppleRenderer.get().active(), "系统空间化立体声模式不应使用回环设备");
			check(AppleRenderer.get().endpointForTests() == null, "系统空间化立体声模式应关闭音频桥");
			check(AppleRenderer.get().status(ConfigManager.get()) == AppleRendererStatus.SYSTEM_STEREO, "状态应为系统空间化立体声");
			check(HeadTrackingManager.get().boundPort() < 0, "系统空间化立体声模式下本模组应停止自己的头部追踪");
			this.openTrackPort = 0;
			playRecord(server, 8.5, EYE, 0.5);
			context.waitTicks(30);
			final double[] stereoLeft = measure(context, 40);
			if (stereoLeft != null) {
				log("系统空间化立体声左侧声源：L=%.5f R=%.5f", stereoLeft[0], stereoLeft[1]);
				check(stereoLeft[0] > stereoLeft[1] * 1.5, "普通立体声下左侧声源的左声道应明显更响");
			}
			server.runCommand("stopsound @a record");

			// 恢复内置渲染器：HRTF 重新开启，头部追踪恢复。
			switchRenderer(context, SpatialRenderer.BUILT_IN, AppleBedFormat.AMBISONICS_3);
			context.waitFor(minecraft -> AudioSystem.get().isAlive() && AudioSystem.get().activeRenderer() == SpatialRenderer.BUILT_IN, 1200);
			log("恢复内置渲染器：设备「%s」，HRTF：%s（%s）", AudioSystem.get().deviceName(), AudioSystem.get().hrtf().enabled(),
				AudioSystem.get().hrtf().name());
			check(AudioSystem.get().hrtf().enabled(), "恢复内置渲染器后 HRTF 应重新开启");
			check(!AppleRenderer.get().active(), "内置渲染器不应使用回环设备");
			check(AppleRenderer.get().status(ConfigManager.get()) == AppleRendererStatus.BUILT_IN, "状态应为内置渲染器");
			check(HeadTrackingManager.get().boundPort() > 0, "恢复内置渲染器后头部追踪应重新启动");
			this.openTrackPort = HeadTrackingManager.get().boundPort();
			log("Apple 渲染方式测试通过：助手共连接 %d 次，收到 CONFIG %d 条", helper.connections(), helper.configCount());
		} finally {
			helper.stop();
			System.clearProperty(AppleRenderer.ALLOW_NON_MAC_PROPERTY);
		}
	}

	/**
	 * 按设置界面的方式切换渲染方式：取修改前快照、修改、替换配置，再通知各子系统（需要时会重新加载声音引擎）。
	 *
	 * @param context 测试上下文
	 * @param renderer 渲染方式
	 * @param format Apple 声场格式
	 */
	private static void switchRenderer(final ClientGameTestContext context, final SpatialRenderer renderer, final AppleBedFormat format) {
		context.runOnClient(minecraft -> {
			final SpatialAudioConfig before = ConfigManager.snapshot();
			final SpatialAudioConfig after = ConfigManager.snapshot();
			after.renderer = renderer;
			after.appleBedFormat = format;
			ConfigManager.replace(after);
			SpatialAudioClient.onConfigChanged(before, after);
		});
	}

	/**
	 * 把设置界面的选项列表滚动到“渲染方式”选项所在行，使其下方的 Apple 空间音频分组出现在截图中。
	 *
	 * @param screen 当前界面
	 */
	private static void scrollToRendererOption(final Screen screen) {
		if (screen == null) {
			return;
		}
		final String label = Component.translatable(SpatialRenderer.APPLE.translationKey()).getString();
		for (final GuiEventListener child : screen.children()) {
			if (!(child instanceof OptionsList list)) {
				continue;
			}
			for (final GuiEventListener entry : ((ContainerEventHandler) list).children()) {
				if (!(entry instanceof ContainerEventHandler row) || !(entry instanceof LayoutElement element)) {
					continue;
				}
				for (final GuiEventListener widget : row.children()) {
					// 只看循环按钮：顶部状态面板的文本中同样含有“Apple 空间音频”。
					if (widget instanceof CycleButton<?> button && button.getMessage().getString().contains(label)) {
						// 行内控件只在可见时才被定位，因此按整行的位置滚动。
						list.setScrollAmount(list.scrollAmount() + element.getY() - list.getY() - 4);
						log("设置界面已滚动到渲染方式选项（滚动量 %.0f）", list.scrollAmount());
						return;
					}
				}
			}
		}
		log("设置界面中没有找到渲染方式选项，截图保持默认滚动位置");
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
	 * 以 60 Hz 向 {@link #openTrackPort} 发送 OpenTrack 数据包。
	 */
	private void sendOpenTrack() {
		try (DatagramSocket socket = new DatagramSocket()) {
			final InetAddress loopback = InetAddress.getLoopbackAddress();
			while (this.sending) {
				final int port = this.openTrackPort;
				if (port > 0) {
					final byte[] data = OpenTrackProtocol.encode(0.0, 0.0, 0.0, this.fakeYaw, 0.0, 0.0);
					socket.send(new DatagramPacket(data, data.length, loopback, port));
				}
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
	 * Ambisonics 声场统计：各声道能量与各声道和 W（声道 0）的互相关。
	 *
	 * @param frames 统计的帧数
	 * @param energy 各声道样本平方和
	 * @param crossW 各声道与 W 的乘积和
	 */
	private record AmbisonicStats(long frames, double[] energy, double[] crossW) {
		/**
		 * 某声道与 W 的相关系数。
		 *
		 * @param channel 声道号（ACN）
		 * @return 相关系数，-1 ~ 1
		 */
		double correlation(final int channel) {
			final double denominator = Math.sqrt(this.energy[0] * this.energy[channel]);
			return denominator > 0.0 ? this.crossW[channel] / denominator : 0.0;
		}

		/**
		 * 某声道相对 W 的增益（以 W 为自变量的回归系数）：对单一方向的平面波即该方向的 SN3D 方向分量。
		 *
		 * @param channel 声道号（ACN）
		 * @return 增益
		 */
		double gain(final int channel) {
			return this.energy[0] > 0.0 ? this.crossW[channel] / this.energy[0] : 0.0;
		}

		/**
		 * 声道的 RMS。
		 *
		 * @param channel 声道号
		 * @return RMS
		 */
		double rms(final int channel) {
			return this.frames > 0 ? Math.sqrt(this.energy[channel] / this.frames) : 0.0;
		}

		/**
		 * 断言收到了足够的、非静音的声场数据（40 刻约 96000 帧，慢速环境下只要求四分之一）。
		 */
		void checkEnough() {
			check(this.frames >= 24000, "助手收到的声场数据不足（" + this.frames + " 帧）");
			check(this.rms(0) > 1.0e-4, "声场的 W 声道不应是静音，RMS " + this.rms(0));
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%d 帧，W RMS %.5f，corr(W,Y)=%.3f，corr(W,Z)=%.3f，corr(W,X)=%.3f，"
				+ "增益 Y=%.3f Z=%.3f X=%.3f", this.frames, this.rms(0), this.correlation(1), this.correlation(2), this.correlation(3),
				this.gain(1), this.gain(2), this.gain(3));
		}
	}

	/**
	 * 模拟 macOS 助手：连接 Apple 音频桥，以实时速度请求声场并统计，同时每 250 毫秒报告一次“引擎运行中”的状态。
	 *
	 * <p>音频桥关闭（切换渲染方式）后自动等待并重新连接新的音频桥，与真实助手的重连行为一致。
	 */
	private static final class FakeAppleHelper implements Runnable {
		/** 预先请求的帧数（相当于助手的目标缓冲）。 */
		private static final int PREBUFFER_FRAMES = 1440;

		/** 单次请求的最小帧数。 */
		private static final int MIN_REQUEST_FRAMES = 480;

		/** 单次请求的最大帧数（落后太多时分多次追赶）。 */
		private static final int MAX_REQUEST_FRAMES = 4800;

		/** 状态报告间隔（纳秒）。 */
		private static final long STATUS_INTERVAL_NANOS = 250_000_000L;

		/** 最大声道数。 */
		private static final int MAX_CHANNELS = 16;

		/** 是否继续运行。 */
		private volatile boolean running = true;

		/** 当前套接字。 */
		private volatile Socket socket;

		/** 最近一次收到的配置。 */
		private volatile AppleBridgeProtocol.Config config;

		/** 最近一次收到的头部姿态；无效姿态为 null。 */
		private volatile Quat lastHead;

		/** 收到的 HEAD 消息数。 */
		private final AtomicInteger heads = new AtomicInteger();

		/** 收到的 CONFIG 消息数。 */
		private final AtomicInteger configs = new AtomicInteger();

		/** 成功连接的次数。 */
		private final AtomicInteger connections = new AtomicInteger();

		/** 后台线程发现的协议问题（由测试线程在测量时断言）；没有问题时为 null。 */
		private volatile String failure;

		/** 保护统计数据的锁。 */
		private final Object statsLock = new Object();

		/** 统计的帧数。 */
		private long frames;

		/** 各声道样本平方和。 */
		private final double[] energy = new double[MAX_CHANNELS];

		/** 各声道与 W 的乘积和。 */
		private final double[] crossW = new double[MAX_CHANNELS];

		@Override
		public void run() {
			while (this.running) {
				final AppleRenderer.HelperLink link = AppleRenderer.get().endpointForTests();
				if (link == null) {
					sleep(50L);
					continue;
				}
				try (Socket current = new Socket(InetAddress.getLoopbackAddress(), link.port())) {
					current.setTcpNoDelay(true);
					this.socket = current;
					final OutputStream out = current.getOutputStream();
					send(out, AppleBridgeProtocol.encodeHello(new AppleBridgeProtocol.Hello(AppleBridgeProtocol.VERSION, link.token(), 0, 15, 0)));
					this.connections.incrementAndGet();
					final Thread writer = new Thread(() -> this.writeLoop(current, out), "SpatialAudio-Test-AppleHelper-Write");
					writer.setDaemon(true);
					writer.start();
					this.readLoop(new DataInputStream(current.getInputStream()));
				} catch (IOException | AppleBridgeProtocol.ProtocolException e) {
					// 音频桥关闭或连接中断：稍后重新连接。
				} finally {
					this.config = null;
					this.socket = null;
				}
				sleep(100L);
			}
		}

		/**
		 * 读取音频桥发来的消息，直到连接关闭。
		 *
		 * @param in 输入流
		 * @throws IOException 连接中断
		 * @throws AppleBridgeProtocol.ProtocolException 协议错误
		 */
		private void readLoop(final DataInputStream in) throws IOException, AppleBridgeProtocol.ProtocolException {
			final byte[] header = new byte[AppleBridgeProtocol.HEADER_SIZE];
			while (this.running) {
				in.readFully(header);
				final AppleBridgeProtocol.Header parsed = AppleBridgeProtocol.readHeader(ByteBuffer.wrap(header));
				final byte[] bytes = new byte[parsed.length()];
				in.readFully(bytes);
				final ByteBuffer payload = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
				switch (parsed.type()) {
					case AppleBridgeProtocol.TYPE_CONFIG -> {
						this.config = AppleBridgeProtocol.decodeConfig(payload);
						this.configs.incrementAndGet();
					}
					case AppleBridgeProtocol.TYPE_HEAD -> {
						this.lastHead = AppleBridgeProtocol.decodeHead(payload);
						this.heads.incrementAndGet();
					}
					case AppleBridgeProtocol.TYPE_AUDIO -> this.accumulate(payload);
					default -> {
						// 其它消息与本测试无关。
					}
				}
			}
		}

		/**
		 * 累计一条 AUDIO 消息的能量与互相关（只统计当前代次的声场）。
		 *
		 * @param payload AUDIO 负载
		 */
		private void accumulate(final ByteBuffer payload) {
			final AppleBridgeProtocol.Config current = this.config;
			final int generation = payload.getInt(0);
			final int count = payload.getInt(4);
			if (current == null || current.channels() == 0 || generation != current.generation()) {
				return;
			}
			final int channels = Math.min(current.channels(), MAX_CHANNELS);
			if (payload.capacity() != AppleBridgeProtocol.AUDIO_PREFIX_SIZE + count * current.channels() * Float.BYTES) {
				this.failure = "AUDIO 负载长度与 CONFIG 的声道数不符：" + count + " 帧，" + payload.capacity() + " 字节";
				return;
			}
			synchronized (this.statsLock) {
				for (int f = 0; f < count; f++) {
					final int base = AppleBridgeProtocol.AUDIO_PREFIX_SIZE + f * current.channels() * Float.BYTES;
					final double w = payload.getFloat(base);
					for (int c = 0; c < channels; c++) {
						final double v = payload.getFloat(base + c * Float.BYTES);
						this.energy[c] += v * v;
						this.crossW[c] += w * v;
					}
				}
				this.frames += count;
			}
		}

		/**
		 * 写入线程：按实时速度发送 REQUEST，并定期发送 STATUS。
		 *
		 * @param current 套接字
		 * @param out 输出流
		 */
		private void writeLoop(final Socket current, final OutputStream out) {
			int pacedGeneration = -1;
			long streamStart = 0L;
			long requested = 0L;
			long nextStatus = 0L;
			try {
				while (this.running && !current.isClosed()) {
					final long now = System.nanoTime();
					final AppleBridgeProtocol.Config active = this.config;
					if (active != null && active.channels() > 0) {
						if (active.generation() != pacedGeneration) {
							// 新的声场：清零已请求的帧数，从现在开始计时。
							pacedGeneration = active.generation();
							streamStart = now;
							requested = 0L;
						}
						final long due = (now - streamStart) * active.sampleRate() / 1_000_000_000L + PREBUFFER_FRAMES - requested;
						if (due >= MIN_REQUEST_FRAMES) {
							final int frames = (int) Math.min(due, MAX_REQUEST_FRAMES);
							send(out, AppleBridgeProtocol.encodeRequest(new AppleBridgeProtocol.Request(pacedGeneration, frames)));
							requested += frames;
						}
					}
					if (now >= nextStatus) {
						nextStatus = now + STATUS_INTERVAL_NANOS;
						final int flags = AppleBridgeProtocol.STATUS_ENGINE_RUNNING | AppleBridgeProtocol.STATUS_BED_ACCEPTED;
						send(out, AppleBridgeProtocol.encodeStatus(new AppleBridgeProtocol.Status(flags, active == null ? 0 : active.generation(),
							PREBUFFER_FRAMES, 0, 7, 48000, 0.01f, "测试耳机", "")));
					}
					sleep(10L);
				}
			} catch (IOException e) {
				// 连接已关闭，由读取线程负责重连。
			}
		}

		/**
		 * 清零统计，等待若干刻后返回这段时间内的声场统计。
		 *
		 * @param context 测试上下文
		 * @param ticks 测量时长（刻）
		 * @return 统计
		 */
		AmbisonicStats measure(final ClientGameTestContext context, final int ticks) {
			synchronized (this.statsLock) {
				this.frames = 0L;
				Arrays.fill(this.energy, 0.0);
				Arrays.fill(this.crossW, 0.0);
			}
			context.waitTicks(ticks);
			check(this.failure == null, this.failure);
			synchronized (this.statsLock) {
				return new AmbisonicStats(this.frames, this.energy.clone(), this.crossW.clone());
			}
		}

		/**
		 * 最近一次收到的有声场的配置。
		 *
		 * @return 配置；尚未收到或当前为空闲配置时为 null
		 */
		AppleBridgeProtocol.Config streamConfig() {
			final AppleBridgeProtocol.Config current = this.config;
			return current != null && current.channels() > 0 ? current : null;
		}

		/**
		 * 最近一次收到的头部姿态。
		 *
		 * @return 头部旋转；无效姿态或尚未收到时为 null
		 */
		Quat lastHead() {
			return this.lastHead;
		}

		/**
		 * 收到的 HEAD 消息数。
		 *
		 * @return 条数
		 */
		int headCount() {
			return this.heads.get();
		}

		/**
		 * 收到的 CONFIG 消息数。
		 *
		 * @return 条数
		 */
		int configCount() {
			return this.configs.get();
		}

		/**
		 * 成功连接的次数。
		 *
		 * @return 次数
		 */
		int connections() {
			return this.connections.get();
		}

		/**
		 * 停止模拟助手并关闭连接。
		 */
		void stop() {
			this.running = false;
			final Socket current = this.socket;
			if (current != null) {
				try {
					current.close();
				} catch (IOException ignored) {
					// 关闭失败不影响测试结果。
				}
			}
		}

		/**
		 * 发送一条已编码的消息。
		 *
		 * @param out 输出流
		 * @param message 消息
		 * @throws IOException 写入失败
		 */
		private static void send(final OutputStream out, final ByteBuffer message) throws IOException {
			out.write(message.array(), message.arrayOffset() + message.position(), message.remaining());
			out.flush();
		}

		/**
		 * 休眠，被中断时直接返回。
		 *
		 * @param millis 毫秒
		 */
		private static void sleep(final long millis) {
			try {
				TimeUnit.MILLISECONDS.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
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
