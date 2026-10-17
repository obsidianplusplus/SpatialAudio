package io.github.obsidianplusplus.spatialaudio.apple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * SAAB 音频桥的回环 TCP 测试：测试代码扮演 macOS 助手，验证令牌校验、配置下发、按请求推送音频、头部姿态与空转渲染。
 *
 * <p>所有等待都使用宽松的超时（慢速 CI 上也不会误报），每个测试结束后关闭全部套接字与音频桥。
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AppleAudioBridgeTest {
	/** 等待条件成立的最长时间（毫秒）。 */
	private static final long WAIT_MILLIS = 15_000L;

	/** 模拟助手读取消息的套接字超时（毫秒）。 */
	private static final int READ_TIMEOUT_MILLIS = 15_000;

	/** 音频桥单次发送的最大帧数（与实现一致，用于检查分块）。 */
	private static final int MAX_CHUNK_FRAMES = 1024;

	/** 测试中创建的音频桥（测试结束时关闭）。 */
	private final List<AppleAudioBridge> bridges = new ArrayList<>();

	/** 测试中创建的模拟助手（测试结束时关闭）。 */
	private final List<FakeHelper> helpers = new ArrayList<>();

	/**
	 * 关闭全部模拟助手与音频桥。
	 */
	@AfterEach
	void closeAll() {
		for (final FakeHelper helper : this.helpers) {
			helper.close();
		}
		for (final AppleAudioBridge bridge : this.bridges) {
			bridge.close();
		}
		this.helpers.clear();
		this.bridges.clear();
	}

	/**
	 * 令牌错误、协议版本错误或第一条消息不是 HELLO 时，连接被关闭且不视为已连接。
	 *
	 * @throws Exception 网络异常
	 */
	@Test
	void rejectsWrongTokenVersionAndFirstMessage() throws Exception {
		final AppleAudioBridge bridge = this.bridge(() -> Quat.IDENTITY);
		final String wrong = bridge.token().equals("f".repeat(32)) ? "e".repeat(32) : "f".repeat(32);

		final FakeHelper wrongToken = this.connect(bridge);
		wrongToken.send(hello(wrong, 0, 1));
		wrongToken.assertClosedByPeer();

		final FakeHelper wrongVersion = this.connect(bridge);
		wrongVersion.send(AppleBridgeProtocol.encodeHello(new AppleBridgeProtocol.Hello(2, bridge.token(), 0, 15, 1)));
		wrongVersion.assertClosedByPeer();

		final FakeHelper requestFirst = this.connect(bridge);
		requestFirst.send(AppleBridgeProtocol.encodeRequest(new AppleBridgeProtocol.Request(bridge.generation(), 480)));
		requestFirst.assertClosedByPeer();

		assertFalse(bridge.connected());
		assertNull(bridge.hello());
		assertSame(AppleBridgeProtocol.Status.NONE, bridge.status());

		// 被拒绝后，正确的助手仍然可以连接。
		final FakeHelper good = this.connect(bridge);
		good.send(hello(bridge.token(), 0, 1));
		good.awaitConfig(config -> true);
		waitUntil(bridge::connected, "正确令牌的助手应连接成功");
	}

	/**
	 * 正确的 HELLO 之后先收到空闲 CONFIG（0 声道），随后持续收到携带头部姿态来源四元数的 HEAD 消息；
	 * 姿态来源返回 null 时发送无效姿态。
	 *
	 * @throws Exception 网络异常
	 */
	@Test
	void helloReceivesIdleConfigAndHead() throws Exception {
		final Quat pose = Quat.fromEulerZXY(Math.toRadians(90.0), Math.toRadians(5.0), 0.0);
		final AtomicReference<Quat> head = new AtomicReference<>(pose);
		final AppleAudioBridge bridge = this.bridge(head::get);
		final FakeHelper helper = this.connect(bridge);
		helper.send(hello(bridge.token(), AppleBridgeProtocol.CAP_MOTION_AVAILABLE, 4242));

		final AppleBridgeProtocol.Config idle = helper.awaitConfig(config -> true);
		assertEquals(0, idle.channels());
		assertEquals(AppleBridgeProtocol.LAYOUT_NONE, idle.layout());
		assertEquals(48000, idle.sampleRate());
		assertEquals("", idle.deviceName());
		assertEquals(bridge.generation(), idle.generation());

		waitUntil(bridge::connected, "助手应处于已连接状态");
		assertNotNull(bridge.hello());
		assertEquals(4242, bridge.hello().helperPid());
		assertEquals(15, bridge.hello().macOsMajor());
		assertTrue(bridge.hello().has(AppleBridgeProtocol.CAP_MOTION_AVAILABLE));
		assertFalse(bridge.streaming());

		// 头部姿态约 100 Hz：至少应很快收到几条，且内容与来源一致。
		for (int i = 0; i < 5; i++) {
			final Quat received = helper.awaitHead();
			assertNotNull(received, "姿态有效时 HEAD 应带 VALID 标志");
			assertEquals(pose.x(), received.x(), 1.0e-6);
			assertEquals(pose.y(), received.y(), 1.0e-6);
			assertEquals(pose.z(), received.z(), 1.0e-6);
			assertEquals(pose.w(), received.w(), 1.0e-6);
		}
		assertEquals(90.0, Math.toDegrees(bridge.lastHead().toEulerZXY()[0]), 1.0e-6);

		// 来源返回 null：之后的 HEAD 应为无效姿态。
		head.set(null);
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
		boolean sawInvalid = false;
		while (!sawInvalid && System.nanoTime() < deadline) {
			sawInvalid = helper.awaitHead() == null;
		}
		assertTrue(sawInvalid, "姿态来源返回 null 后应发送无效 HEAD");
	}

	/**
	 * 推送声场后助手收到新的 CONFIG（代次更高、声道数、布局与设备名正确）；
	 * REQUEST(代次, N) 得到总计恰好 N 帧、按块不超过上限、内容与渲染源一致的 AUDIO，且不会多发。
	 *
	 * @throws Exception 网络异常
	 */
	@Test
	void attachStreamsExactlyRequestedFrames() throws Exception {
		final AppleAudioBridge bridge = this.bridge(() -> Quat.IDENTITY);
		final FakeHelper helper = this.connect(bridge);
		helper.send(hello(bridge.token(), 0, 1));
		final AppleBridgeProtocol.Config idle = helper.awaitConfig(config -> true);

		final RampSource source = new RampSource(4);
		bridge.attach(new AppleAudioBridge.Stream(source, 48000, 4, AppleBridgeProtocol.LAYOUT_HOA_ACN_SN3D, "AirPods Pro 测试"));
		assertTrue(bridge.streaming());
		final AppleBridgeProtocol.Config config = helper.awaitConfig(c -> c.channels() > 0);
		assertTrue(config.generation() > idle.generation(), "新声场的代次应更高");
		assertEquals(bridge.generation(), config.generation());
		assertEquals(4, config.channels());
		assertEquals(AppleBridgeProtocol.LAYOUT_HOA_ACN_SN3D, config.layout());
		assertEquals(48000, config.sampleRate());
		assertEquals("AirPods Pro 测试", config.deviceName());

		helper.send(request(config.generation(), 2500));
		final AudioCollector audio = helper.collectAudio(config.generation(), 4, 2500);
		assertEquals(2500, audio.frames);
		assertTrue(audio.chunks >= 3, "2500 帧至少应分 3 块发送，实际 " + audio.chunks);
		assertTrue(audio.maxChunk <= MAX_CHUNK_FRAMES, "单块帧数不应超过上限，实际 " + audio.maxChunk);

		// 没有额外请求时不会再收到音频。
		assertEquals(0, helper.audioFramesWithin(500));
		assertEquals(2500L, bridge.framesSent());

		// 额度可以累积：两次请求合计 1000 帧。
		helper.send(request(config.generation(), 400));
		helper.send(request(config.generation(), 600));
		assertEquals(1000, helper.collectAudio(config.generation(), 4, 1000).frames);
		assertEquals(0, helper.audioFramesWithin(300));
	}

	/**
	 * 旧代次的 REQUEST 被忽略；当前代次的 REQUEST 正常响应。
	 *
	 * @throws Exception 网络异常
	 */
	@Test
	void staleRequestsAreIgnored() throws Exception {
		final AppleAudioBridge bridge = this.bridge(() -> Quat.IDENTITY);
		final FakeHelper helper = this.connect(bridge);
		helper.send(hello(bridge.token(), 0, 1));
		final AppleBridgeProtocol.Config idle = helper.awaitConfig(config -> true);
		bridge.attach(new AppleAudioBridge.Stream(new RampSource(2), 48000, 2, AppleBridgeProtocol.LAYOUT_SURROUND_7_1, ""));
		final AppleBridgeProtocol.Config config = helper.awaitConfig(c -> c.channels() == 2);

		helper.send(request(idle.generation(), 1000));
		helper.send(request(config.generation() + 1, 1000));
		assertEquals(0, helper.audioFramesWithin(600), "旧代次或未知代次的请求不应得到音频");

		helper.send(request(config.generation(), 480));
		assertEquals(480, helper.collectAudio(config.generation(), 2, 480).frames);
		assertEquals(0, helper.audioFramesWithin(300));
	}

	/**
	 * 修改可调参数时下发新的 CONFIG（参数相同则不下发）；拆除声场后下发空闲 CONFIG，且不再调用渲染源、不再响应请求。
	 *
	 * @throws Exception 网络异常
	 */
	@Test
	void settingsAndDetachSendNewConfig() throws Exception {
		final AppleAudioBridge bridge = this.bridge(() -> Quat.IDENTITY);
		final FakeHelper helper = this.connect(bridge);
		helper.send(hello(bridge.token(), 0, 1));
		final AppleBridgeProtocol.Config idle = helper.awaitConfig(config -> true);
		assertEquals(AppleBridgeProtocol.CONFIG_PREFER_AUTO_HEAD_TRACKING, idle.flags());

		final RampSource source = new RampSource(8);
		bridge.attach(new AppleAudioBridge.Stream(source, 48000, 8, AppleBridgeProtocol.LAYOUT_SURROUND_7_1, ""));
		final AppleBridgeProtocol.Config streaming = helper.awaitConfig(c -> c.channels() == 8);

		bridge.updateSettings(new AppleAudioBridge.Settings(4800, false));
		final AppleBridgeProtocol.Config updated = helper.awaitConfig(c -> c.targetFrames() == 4800);
		assertTrue(updated.generation() > streaming.generation());
		assertEquals(0, updated.flags());
		assertEquals(8, updated.channels());
		assertEquals(AppleBridgeProtocol.LAYOUT_SURROUND_7_1, updated.layout());

		final int generation = bridge.generation();
		bridge.updateSettings(new AppleAudioBridge.Settings(4800, false));
		assertEquals(generation, bridge.generation(), "参数未变化时代次不应增加");

		// 旧代次（更新参数前）的请求作废。
		helper.send(request(streaming.generation(), 480));
		assertEquals(0, helper.audioFramesWithin(400));
		helper.send(request(updated.generation(), 960));
		assertEquals(960, helper.collectAudio(updated.generation(), 8, 960).frames);

		bridge.detach();
		assertFalse(bridge.streaming());
		final long callsAfterDetach = source.calls.get();
		final AppleBridgeProtocol.Config detached = helper.awaitConfig(c -> c.generation() > updated.generation());
		assertEquals(0, detached.channels());
		assertEquals(AppleBridgeProtocol.LAYOUT_NONE, detached.layout());
		assertEquals(4800, detached.targetFrames());

		helper.send(request(detached.generation(), 480));
		assertEquals(0, helper.audioFramesWithin(500), "拆除声场后不应再发送音频");
		assertEquals(callsAfterDetach, source.calls.get(), "detach 返回后不应再调用旧的渲染源");
	}

	/**
	 * 助手的 STATUS 被记录；超过 1 秒没有新状态时视为过期。
	 *
	 * @throws Exception 网络异常
	 */
	@Test
	void statusIsRecordedAndExpires() throws Exception {
		final AppleAudioBridge bridge = this.bridge(() -> Quat.IDENTITY);
		final FakeHelper helper = this.connect(bridge);
		helper.send(hello(bridge.token(), 0, 1));
		final AppleBridgeProtocol.Config config = helper.awaitConfig(c -> true);
		final AppleBridgeProtocol.Status status = new AppleBridgeProtocol.Status(
			AppleBridgeProtocol.STATUS_ENGINE_RUNNING | AppleBridgeProtocol.STATUS_BED_ACCEPTED, config.generation(), 1440, 2, 7,
			48000, 0.02f, "AirPods Pro", "");
		helper.send(AppleBridgeProtocol.encodeStatus(status));
		waitUntil(() -> bridge.status().generation() == config.generation(), "应记录助手状态");
		assertEquals(status, bridge.status());
		assertTrue(bridge.status().has(AppleBridgeProtocol.STATUS_ENGINE_RUNNING));

		Thread.sleep(1300L);
		assertSame(AppleBridgeProtocol.Status.NONE, bridge.status(), "超过 1 秒未更新的状态应过期");
	}

	/**
	 * 新的已认证连接替换旧连接；更换令牌会断开现有连接，旧令牌随即失效。
	 *
	 * @throws Exception 网络异常
	 */
	@Test
	void newConnectionReplacesOldAndRotateTokenDisconnects() throws Exception {
		final AppleAudioBridge bridge = this.bridge(() -> Quat.IDENTITY);
		final FakeHelper first = this.connect(bridge);
		first.send(hello(bridge.token(), 0, 1));
		first.awaitConfig(c -> true);

		final FakeHelper second = this.connect(bridge);
		second.send(hello(bridge.token(), 0, 2));
		second.awaitConfig(c -> true);
		first.assertClosedByPeer();
		waitUntil(() -> bridge.connected() && bridge.hello().helperPid() == 2, "第二个助手应成为当前连接");

		final String oldToken = bridge.token();
		final String newToken = bridge.rotateToken();
		assertNotEquals(oldToken, newToken);
		assertEquals(32, newToken.length());
		assertTrue(newToken.matches("[0-9a-f]{32}"), "令牌应为 32 位小写十六进制：" + newToken);
		second.assertClosedByPeer();
		waitUntil(() -> !bridge.connected(), "更换令牌后应断开现有连接");

		final FakeHelper stale = this.connect(bridge);
		stale.send(hello(oldToken, 0, 3));
		stale.assertClosedByPeer();
		assertFalse(bridge.connected());
	}

	/**
	 * 没有助手连接时，推送的声场以接近实时的速度空转渲染（48 kHz，允许 ±40%），保证游戏声音照常推进。
	 *
	 * @throws Exception 等待被中断
	 */
	@Test
	void freeRunsAtRealTimeWithoutHelper() throws Exception {
		final AppleAudioBridge bridge = this.bridge(() -> Quat.IDENTITY);
		final RampSource source = new RampSource(2);
		bridge.attach(new AppleAudioBridge.Stream(source, 48000, 2, AppleBridgeProtocol.LAYOUT_SURROUND_7_1, ""));
		waitUntil(() -> source.frames.get() > 0, "未连接时也应调用渲染源");
		Thread.sleep(200L);

		final long startFrames = source.frames.get();
		final long startNanos = System.nanoTime();
		Thread.sleep(2000L);
		final long frames = source.frames.get() - startFrames;
		final double seconds = (System.nanoTime() - startNanos) / 1.0e9;
		final double rate = frames / seconds;
		assertTrue(rate > 48000 * 0.6 && rate < 48000 * 1.4, "空转渲染速度应接近 48000 帧/秒，实际 " + rate);
		assertFalse(bridge.connected());
		assertEquals(0L, bridge.framesSent(), "空转渲染的数据不应计入已发送帧数");

		bridge.detach();
		final long afterDetach = source.calls.get();
		Thread.sleep(200L);
		assertEquals(afterDetach, source.calls.get(), "拆除声场后不应再空转渲染");
	}

	// ===================== 工具 =====================

	/**
	 * 创建音频桥并登记，测试结束时关闭。
	 *
	 * @param head 头部姿态来源
	 * @return 音频桥
	 * @throws IOException 无法监听端口
	 */
	private AppleAudioBridge bridge(final Supplier<Quat> head) throws IOException {
		final AppleAudioBridge bridge = new AppleAudioBridge(head);
		this.bridges.add(bridge);
		assertTrue(bridge.port() > 0);
		return bridge;
	}

	/**
	 * 创建连接到音频桥的模拟助手并登记，测试结束时关闭。
	 *
	 * @param bridge 音频桥
	 * @return 模拟助手
	 * @throws IOException 连接失败
	 */
	private FakeHelper connect(final AppleAudioBridge bridge) throws IOException {
		final FakeHelper helper = new FakeHelper(bridge.port());
		this.helpers.add(helper);
		return helper;
	}

	/**
	 * 编码 HELLO（协议版本 1、macOS 15）。
	 *
	 * @param token 令牌
	 * @param capabilities 能力位
	 * @param pid 进程号
	 * @return 消息
	 */
	private static ByteBuffer hello(final String token, final int capabilities, final int pid) {
		return AppleBridgeProtocol.encodeHello(new AppleBridgeProtocol.Hello(AppleBridgeProtocol.VERSION, token, capabilities, 15, pid));
	}

	/**
	 * 编码 REQUEST。
	 *
	 * @param generation 代次
	 * @param frames 帧数
	 * @return 消息
	 */
	private static ByteBuffer request(final int generation, final int frames) {
		return AppleBridgeProtocol.encodeRequest(new AppleBridgeProtocol.Request(generation, frames));
	}

	/**
	 * 等待条件成立。
	 *
	 * @param condition 条件
	 * @param message 超时信息
	 * @throws InterruptedException 等待被中断
	 */
	private static void waitUntil(final BooleanSupplier condition, final String message) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail(message);
			}
			Thread.sleep(5L);
		}
	}

	/**
	 * 渲染源写入的确定性样本：帧序号（按 65536 取模）加上声道号 / 16，32 位浮点可精确表示。
	 *
	 * @param frame 帧序号
	 * @param channel 声道号
	 * @return 样本值
	 */
	static float rampSample(final long frame, final int channel) {
		return (float) (frame % 65536L) + channel / 16.0f;
	}

	/**
	 * 确定性的渲染源：模拟 alcRenderSamplesSOFT，从缓冲区当前位置写入小端序浮点（不移动位置），样本为连续递增的斜坡。
	 */
	private static final class RampSource implements AppleAudioBridge.FrameSource {
		/** 声道数。 */
		private final int channels;

		/** 已渲染的总帧数（即下一帧的序号）。 */
		final AtomicLong frames = new AtomicLong();

		/** 调用次数。 */
		final AtomicLong calls = new AtomicLong();

		/**
		 * 创建渲染源。
		 *
		 * @param channels 声道数
		 */
		RampSource(final int channels) {
			this.channels = channels;
		}

		@Override
		public void render(final ByteBuffer target, final int frames) {
			final ByteBuffer out = target.duplicate().order(ByteOrder.LITTLE_ENDIAN);
			final int start = target.position();
			final long first = this.frames.getAndAdd(frames);
			for (int f = 0; f < frames; f++) {
				for (int c = 0; c < this.channels; c++) {
					out.putFloat(start + (f * this.channels + c) * Float.BYTES, rampSample(first + f, c));
				}
			}
			this.calls.incrementAndGet();
		}
	}

	/**
	 * 收到的音频统计。
	 */
	private static final class AudioCollector {
		/** 总帧数。 */
		int frames;

		/** 块数。 */
		int chunks;

		/** 最大块帧数。 */
		int maxChunk;
	}

	/**
	 * 一条收到的消息。
	 *
	 * @param type 消息类型
	 * @param payload 负载（小端序）
	 */
	private record Message(int type, ByteBuffer payload) {
	}

	/**
	 * 用 java.net.Socket 实现的模拟助手。
	 */
	private static final class FakeHelper implements AutoCloseable {
		/** 套接字。 */
		private final Socket socket;

		/** 输入流。 */
		private final DataInputStream in;

		/** 输出流。 */
		private final OutputStream out;

		/**
		 * 连接到本机音频桥。
		 *
		 * @param port 端口
		 * @throws IOException 连接失败
		 */
		FakeHelper(final int port) throws IOException {
			this.socket = new Socket(InetAddress.getLoopbackAddress(), port);
			this.socket.setTcpNoDelay(true);
			this.socket.setSoTimeout(READ_TIMEOUT_MILLIS);
			this.in = new DataInputStream(this.socket.getInputStream());
			this.out = this.socket.getOutputStream();
		}

		/**
		 * 发送一条已编码的消息。
		 *
		 * @param message 消息
		 * @throws IOException 写入失败
		 */
		void send(final ByteBuffer message) throws IOException {
			final ByteBuffer copy = message.duplicate();
			final byte[] bytes = new byte[copy.remaining()];
			copy.get(bytes);
			this.out.write(bytes);
			this.out.flush();
		}

		/**
		 * 读取一条消息（阻塞，受套接字超时限制）。
		 *
		 * @return 消息
		 * @throws IOException 读取失败或超时
		 * @throws AppleBridgeProtocol.ProtocolException 消息头非法
		 */
		Message read() throws IOException, AppleBridgeProtocol.ProtocolException {
			final byte[] header = new byte[AppleBridgeProtocol.HEADER_SIZE];
			this.in.readFully(header);
			final AppleBridgeProtocol.Header parsed = AppleBridgeProtocol.readHeader(ByteBuffer.wrap(header));
			final byte[] payload = new byte[parsed.length()];
			this.in.readFully(payload);
			return new Message(parsed.type(), ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN));
		}

		/**
		 * 读取消息直到出现满足条件的 CONFIG（跳过 HEAD 等其它消息）。
		 *
		 * @param condition 条件
		 * @return 配置
		 * @throws Exception 超时或协议错误
		 */
		AppleBridgeProtocol.Config awaitConfig(final Predicate<AppleBridgeProtocol.Config> condition) throws Exception {
			final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
			while (System.nanoTime() < deadline) {
				final Message message = this.read();
				if (message.type() == AppleBridgeProtocol.TYPE_CONFIG) {
					final AppleBridgeProtocol.Config config = AppleBridgeProtocol.decodeConfig(message.payload());
					if (condition.test(config)) {
						return config;
					}
				}
			}
			throw new AssertionError("超时：没有收到期望的 CONFIG");
		}

		/**
		 * 读取消息直到出现 HEAD。
		 *
		 * @return 头部旋转；无效姿态为 null
		 * @throws Exception 超时或协议错误
		 */
		Quat awaitHead() throws Exception {
			final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
			while (System.nanoTime() < deadline) {
				final Message message = this.read();
				if (message.type() == AppleBridgeProtocol.TYPE_HEAD) {
					return AppleBridgeProtocol.decodeHead(message.payload());
				}
			}
			throw new AssertionError("超时：没有收到 HEAD");
		}

		/**
		 * 收集 AUDIO 直到累计帧数达到期望值，同时逐样本校验内容是连续的斜坡。
		 *
		 * @param generation 期望的代次
		 * @param channels 声道数
		 * @param expectedFrames 期望帧数
		 * @return 统计
		 * @throws Exception 超时、协议错误或内容不符
		 */
		AudioCollector collectAudio(final int generation, final int channels, final int expectedFrames) throws Exception {
			final AudioCollector collector = new AudioCollector();
			final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
			while (collector.frames < expectedFrames) {
				if (System.nanoTime() > deadline) {
					throw new AssertionError("超时：只收到 " + collector.frames + " / " + expectedFrames + " 帧");
				}
				final Message message = this.read();
				if (message.type() != AppleBridgeProtocol.TYPE_AUDIO) {
					continue;
				}
				final ByteBuffer payload = message.payload();
				assertEquals(generation, payload.getInt(0), "AUDIO 的代次应与请求一致");
				final int frames = payload.getInt(4);
				assertTrue(frames > 0, "AUDIO 帧数应为正数");
				assertEquals(AppleBridgeProtocol.AUDIO_PREFIX_SIZE + frames * channels * Float.BYTES, payload.capacity(),
					"AUDIO 负载长度应等于前缀加全部样本");
				// 渲染源可能在两次请求之间空转过，因此只要求块内连续：以首个样本推出起始帧序号。
				final long first = (long) payload.getFloat(AppleBridgeProtocol.AUDIO_PREFIX_SIZE);
				for (int f = 0; f < frames; f++) {
					for (int c = 0; c < channels; c++) {
						final float actual = payload.getFloat(AppleBridgeProtocol.AUDIO_PREFIX_SIZE + (f * channels + c) * Float.BYTES);
						assertEquals(rampSample(first + f, c), actual, "第 " + f + " 帧第 " + c + " 声道的样本不符");
					}
				}
				collector.frames += frames;
				collector.chunks++;
				collector.maxChunk = Math.max(collector.maxChunk, frames);
			}
			assertEquals(expectedFrames, collector.frames, "收到的帧数不应超过请求");
			return collector;
		}

		/**
		 * 在指定时长内读取消息，统计其中 AUDIO 的总帧数。
		 *
		 * @param millis 时长（毫秒）
		 * @return 帧数
		 * @throws Exception 连接中断或协议错误
		 */
		int audioFramesWithin(final long millis) throws Exception {
			final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
			int frames = 0;
			try {
				while (true) {
					final long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
					if (remaining <= 0) {
						break;
					}
					this.socket.setSoTimeout((int) Math.max(1L, remaining));
					final Message message = this.read();
					if (message.type() == AppleBridgeProtocol.TYPE_AUDIO) {
						frames += message.payload().getInt(4);
					}
				}
			} catch (SocketTimeoutException e) {
				// 时间到：HEAD 每 10 毫秒一条，通常不会走到这里。
			} finally {
				this.socket.setSoTimeout(READ_TIMEOUT_MILLIS);
			}
			return frames;
		}

		/**
		 * 断言对端已关闭连接：读到流结束或连接被重置（期间收到的 HEAD 等消息被忽略）。
		 *
		 * @throws Exception 超时仍未关闭或协议错误
		 */
		void assertClosedByPeer() throws Exception {
			final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
			try {
				while (System.nanoTime() < deadline) {
					this.read();
				}
			} catch (EOFException | SocketException e) {
				return;
			} catch (SocketTimeoutException e) {
				fail("对端在超时前没有关闭连接");
			}
			fail("对端在超时前没有关闭连接");
		}

		@Override
		public void close() {
			try {
				this.socket.close();
			} catch (IOException ignored) {
				// 关闭失败不影响测试结果。
			}
		}
	}
}
