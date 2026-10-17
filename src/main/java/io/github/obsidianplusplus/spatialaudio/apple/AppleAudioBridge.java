package io.github.obsidianplusplus.spatialaudio.apple;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * SAAB 音频桥的模组端：在本机回环地址上监听助手连接，向助手推送多声道声场、头部姿态与配置，并接收助手状态。
 *
 * <p>线程模型：
 * <ul>
 *   <li>接受线程：等待助手连接，每个连接再创建一个读取线程；新的已认证连接会替换旧连接；</li>
 *   <li>读取线程：解析 HELLO / REQUEST / STATUS，累计请求帧数并唤醒泵线程；超过 {@link #READ_TIMEOUT_MILLIS} 无数据即断开；</li>
 *   <li>泵线程：唯一的写入者，负责发送 CONFIG、HEAD 与 AUDIO，并在声场渲染源存在时调用渲染。
 *       未连接或助手停止请求时，以实时速度空转渲染并丢弃，保证游戏内声音照常推进而不会堆积。</li>
 * </ul>
 * 其它线程（主线程、声音线程）只修改 volatile 状态，永远不会因网络阻塞。
 */
public final class AppleAudioBridge implements AutoCloseable {
	/** 等待 HELLO 的时长（毫秒）。 */
	private static final int HELLO_TIMEOUT_MILLIS = 3000;

	/** 连接建立后多久没有任何数据视为断开（毫秒）；助手每 250 毫秒发送一次状态。 */
	private static final int READ_TIMEOUT_MILLIS = 5000;

	/** 单次渲染的最大帧数。 */
	private static final int MAX_CHUNK_FRAMES = 1024;

	/** 空转渲染的块大小（帧，48 kHz 下为 10 毫秒）。 */
	private static final int FREE_RUN_FRAMES = 480;

	/** 已连接但助手多久未请求数据时改为空转渲染（纳秒）。 */
	private static final long STALL_NANOS = 300_000_000L;

	/** 头部姿态发送间隔（纳秒，约 100 Hz）。 */
	private static final long HEAD_INTERVAL_NANOS = 10_000_000L;

	/** 泵线程等待请求时的最长等待（纳秒）。 */
	private static final long PUMP_PARK_NANOS = 5_000_000L;

	/** 既无连接也无声场时的等待（纳秒）。 */
	private static final long IDLE_PARK_NANOS = 50_000_000L;

	/** 累计请求帧数上限（防止异常助手让渲染线程无限追赶）。 */
	private static final long MAX_CREDIT_FRAMES = 48_000L;

	/** 一次写入阻塞超过该时长（纳秒）即认为助手已停止接收，断开连接。 */
	private static final long WRITE_STALL_NANOS = 2_000_000_000L;

	/**
	 * 声场渲染源：在泵线程中把指定帧数的交错浮点样本写入缓冲区。
	 */
	public interface FrameSource {
		/**
		 * 渲染音频。
		 *
		 * @param target 目标缓冲区（小端序，从当前位置写入 frames × channels 个 float）
		 * @param frames 帧数
		 */
		void render(ByteBuffer target, int frames);
	}

	/**
	 * 当前要推送的声场。
	 *
	 * @param source 渲染源
	 * @param sampleRate 采样率
	 * @param channels 声道数
	 * @param layout 布局编号（见 {@link AppleBridgeProtocol}）
	 * @param deviceName 目标输出设备名称，空字符串表示系统默认设备
	 */
	public record Stream(FrameSource source, int sampleRate, int channels, int layout, String deviceName) {
	}

	/**
	 * 可在不重建声场的情况下调整的参数。
	 *
	 * @param targetFrames 助手应保持的缓冲帧数
	 * @param preferAutoHeadTracking 是否优先使用 Apple 自动头部追踪
	 */
	public record Settings(int targetFrames, boolean preferAutoHeadTracking) {
	}

	/**
	 * 声场与参数的不可变快照：泵线程每轮只读取一次，保证发送的 CONFIG 与代次、声场内容始终一致。
	 *
	 * @param generation 配置代次
	 * @param stream 声场，可为 null（空闲）
	 * @param settings 可调参数
	 */
	private record State(int generation, Stream stream, Settings settings) {
	}

	/** 监听套接字。 */
	private final ServerSocket server;

	/** 头部姿态来源（泵线程调用）。 */
	private final Supplier<Quat> headSupplier;

	/** 连接令牌（32 位十六进制字符）。 */
	private volatile String token;

	/** 是否已关闭。 */
	private volatile boolean closed;

	/** 当前连接。 */
	private volatile Connection current;

	/** 当前状态快照（代次、声场、参数）；只在 {@link #renderLock} 内替换。 */
	private volatile State state = new State(1, null, new Settings(1440, true));

	/** 保护状态替换与渲染源调用的锁：拆除声场时等待正在进行的渲染结束。 */
	private final ReentrantLock renderLock = new ReentrantLock();

	/** 最近一次助手问候。 */
	private volatile AppleBridgeProtocol.Hello hello;

	/** 最近一次助手状态。 */
	private volatile AppleBridgeProtocol.Status status = AppleBridgeProtocol.Status.NONE;

	/** 最近一次收到状态的时刻（纳秒）。 */
	private volatile long lastStatusNanos;

	/** 最近一次发送的头部姿态（界面显示用）。 */
	private volatile Quat lastHead = Quat.IDENTITY;

	/** 累计推送的帧数（统计用）。 */
	private final AtomicLong framesSent = new AtomicLong();

	/** 泵线程。 */
	private final Thread pump;

	/** 接受线程。 */
	private final Thread acceptor;

	/**
	 * 在 127.0.0.1 的临时端口上创建音频桥并启动后台线程。
	 *
	 * @param headSupplier 头部姿态来源（返回相对屏幕的头部旋转，可返回 null 表示无效）
	 * @throws IOException 无法监听端口
	 */
	public AppleAudioBridge(final Supplier<Quat> headSupplier) throws IOException {
		this.headSupplier = headSupplier;
		this.token = newToken();
		this.server = new ServerSocket();
		this.server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 2);
		this.acceptor = new Thread(this::acceptLoop, "SpatialAudio-AppleBridge-Accept");
		this.acceptor.setDaemon(true);
		this.pump = new Thread(this::pumpLoop, "SpatialAudio-AppleBridge-Pump");
		this.pump.setDaemon(true);
		this.pump.setPriority(Thread.MAX_PRIORITY);
		this.acceptor.start();
		this.pump.start();
	}

	// ===================== 外部接口 =====================

	/**
	 * 监听端口。
	 *
	 * @return 端口号
	 */
	public int port() {
		return this.server.getLocalPort();
	}

	/**
	 * 当前连接令牌。
	 *
	 * @return 令牌
	 */
	public String token() {
		return this.token;
	}

	/**
	 * 生成新的令牌并断开现有连接（重新启动助手时调用，旧助手实例将无法再连接）。
	 *
	 * @return 新令牌
	 */
	public String rotateToken() {
		this.token = newToken();
		final Connection connection = this.current;
		if (connection != null) {
			connection.close();
		}
		return this.token;
	}

	/**
	 * 设置要推送的声场（声音引擎创建回环设备后调用）。
	 *
	 * @param next 声场
	 */
	public void attach(final Stream next) {
		this.renderLock.lock();
		try {
			final State old = this.state;
			this.state = new State(old.generation() + 1, next, old.settings());
		} finally {
			this.renderLock.unlock();
		}
		LockSupport.unpark(this.pump);
	}

	/**
	 * 拆除声场（声音引擎销毁回环设备前调用）。返回时泵线程保证不会再调用旧的渲染源。
	 */
	public void detach() {
		this.renderLock.lock();
		try {
			final State old = this.state;
			if (old.stream() != null) {
				this.state = new State(old.generation() + 1, null, old.settings());
			}
		} finally {
			this.renderLock.unlock();
		}
		LockSupport.unpark(this.pump);
	}

	/**
	 * 更新可调参数；变化时向助手重新发送配置。
	 *
	 * @param next 新参数
	 */
	public void updateSettings(final Settings next) {
		this.renderLock.lock();
		try {
			final State old = this.state;
			if (next.equals(old.settings())) {
				return;
			}
			this.state = new State(old.generation() + 1, old.stream(), next);
		} finally {
			this.renderLock.unlock();
		}
		LockSupport.unpark(this.pump);
	}

	/**
	 * 是否有已认证的助手连接。
	 *
	 * @return 已连接时返回 true
	 */
	public boolean connected() {
		final Connection connection = this.current;
		return connection != null && connection.authenticated;
	}

	/**
	 * 当前是否有声场正在推送。
	 *
	 * @return 有声场时返回 true
	 */
	public boolean streaming() {
		return this.state.stream() != null;
	}

	/**
	 * 最近一次助手问候。
	 *
	 * @return 问候；尚未连接过时为 null
	 */
	public AppleBridgeProtocol.Hello hello() {
		return this.hello;
	}

	/**
	 * 最近一次助手状态；超过 1 秒未更新或未连接时返回占位值。
	 *
	 * @return 状态
	 */
	public AppleBridgeProtocol.Status status() {
		if (!this.connected() || System.nanoTime() - this.lastStatusNanos > 1_000_000_000L) {
			return AppleBridgeProtocol.Status.NONE;
		}
		return this.status;
	}

	/**
	 * 当前配置代次。
	 *
	 * @return 代次
	 */
	public int generation() {
		return this.state.generation();
	}

	/**
	 * 最近一次发送给助手的头部姿态。
	 *
	 * @return 头部旋转
	 */
	public Quat lastHead() {
		return this.lastHead;
	}

	/**
	 * 累计推送给助手的帧数。
	 *
	 * @return 帧数
	 */
	public long framesSent() {
		return this.framesSent.get();
	}

	@Override
	public void close() {
		this.closed = true;
		try {
			this.server.close();
		} catch (IOException ignored) {
			// 关闭监听套接字失败不影响退出。
		}
		final Connection connection = this.current;
		if (connection != null) {
			connection.close();
		}
		this.detach();
		LockSupport.unpark(this.pump);
	}

	// ===================== 接受与读取 =====================

	/**
	 * 接受线程主循环。
	 */
	private void acceptLoop() {
		while (!this.closed) {
			try {
				final Socket socket = this.server.accept();
				socket.setTcpNoDelay(true);
				socket.setSoTimeout(HELLO_TIMEOUT_MILLIS);
				final Connection connection = new Connection(socket);
				final Thread reader = new Thread(() -> this.readLoop(connection), "SpatialAudio-AppleBridge-Read");
				reader.setDaemon(true);
				reader.start();
			} catch (IOException e) {
				if (!this.closed) {
					SpatialAudio.LOGGER.warn("Apple 音频桥接受连接失败：{}", e.toString());
				}
			}
		}
	}

	/**
	 * 读取线程主循环：先校验 HELLO，然后持续处理助手消息。
	 *
	 * @param connection 连接
	 */
	private void readLoop(final Connection connection) {
		try {
			final DataInputStream in = new DataInputStream(connection.socket.getInputStream());
			final byte[] headerBytes = new byte[AppleBridgeProtocol.HEADER_SIZE];
			in.readFully(headerBytes);
			final AppleBridgeProtocol.Header first = AppleBridgeProtocol.readHeader(ByteBuffer.wrap(headerBytes));
			if (first.type() != AppleBridgeProtocol.TYPE_HELLO) {
				throw new AppleBridgeProtocol.ProtocolException("第一条消息不是 HELLO");
			}
			final AppleBridgeProtocol.Hello greeting = AppleBridgeProtocol.decodeHello(readPayload(in, first.length()));
			if (greeting.version() != AppleBridgeProtocol.VERSION) {
				throw new AppleBridgeProtocol.ProtocolException("协议版本不匹配：" + greeting.version());
			}
			if (!constantTimeEquals(greeting.token(), this.token)) {
				throw new AppleBridgeProtocol.ProtocolException("令牌不匹配");
			}
			connection.socket.setSoTimeout(READ_TIMEOUT_MILLIS);
			connection.authenticated = true;
			this.hello = greeting;
			final Connection previous = this.current;
			this.current = connection;
			if (previous != null && previous != connection) {
				previous.close();
			}
			SpatialAudio.LOGGER.info("Apple 空间音频助手已连接（PID {}，macOS {}，能力 0x{}）",
				greeting.helperPid(), greeting.macOsMajor(), Integer.toHexString(greeting.capabilities()));
			LockSupport.unpark(this.pump);

			while (!this.closed && !connection.closed) {
				in.readFully(headerBytes);
				final AppleBridgeProtocol.Header header = AppleBridgeProtocol.readHeader(ByteBuffer.wrap(headerBytes));
				final ByteBuffer payload = readPayload(in, header.length());
				switch (header.type()) {
					case AppleBridgeProtocol.TYPE_REQUEST -> {
						final AppleBridgeProtocol.Request request = AppleBridgeProtocol.decodeRequest(payload);
						if (connection.addCredit(request.generation(), request.frames())) {
							LockSupport.unpark(this.pump);
						}
					}
					case AppleBridgeProtocol.TYPE_STATUS -> {
						this.status = AppleBridgeProtocol.decodeStatus(payload);
						this.lastStatusNanos = System.nanoTime();
					}
					default -> {
						// 未知消息类型：为将来的协议扩展保留，直接忽略。
					}
				}
				if (connection.writeBlockedNanos(System.nanoTime()) > WRITE_STALL_NANOS) {
					// 助手仍在发送状态却不再读取数据：泵线程被阻塞在写入中，断开连接使其恢复。
					SpatialAudio.LOGGER.warn("Apple 空间音频助手停止接收数据，断开连接");
					break;
				}
			}
		} catch (SocketTimeoutException e) {
			if (!connection.closed) {
				SpatialAudio.LOGGER.warn("Apple 空间音频助手长时间无响应，断开连接");
			}
		} catch (EOFException | SocketException e) {
			if (connection.authenticated && !connection.closed && !this.closed) {
				SpatialAudio.LOGGER.info("Apple 空间音频助手已断开");
			}
		} catch (IOException | AppleBridgeProtocol.ProtocolException e) {
			if (!this.closed) {
				SpatialAudio.LOGGER.warn("Apple 音频桥连接出错：{}", e.getMessage());
			}
		} finally {
			connection.close();
			if (this.current == connection) {
				this.current = null;
			}
		}
	}

	/**
	 * 读取指定长度的负载。
	 *
	 * @param in 输入流
	 * @param length 长度
	 * @return 小端序缓冲区
	 * @throws IOException 读取失败
	 */
	private static ByteBuffer readPayload(final DataInputStream in, final int length) throws IOException {
		final byte[] bytes = new byte[length];
		in.readFully(bytes);
		return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}

	// ===================== 泵线程 =====================

	/**
	 * 泵线程主循环：同步配置、发送头部姿态、按请求渲染并发送音频，或在无人消费时空转渲染。
	 */
	private void pumpLoop() {
		final ByteBuffer headBuffer = ByteBuffer.allocate(AppleBridgeProtocol.HEADER_SIZE + AppleBridgeProtocol.HEAD_SIZE);
		ByteBuffer audio = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
		byte[] audioBytes = new byte[0];
		long lastHeadNanos = 0L;
		long freeRunDeadline = 0L;
		long lastAudioNanos = System.nanoTime();
		while (!this.closed) {
			final long now = System.nanoTime();
			final Connection connection = this.current;
			final State snapshot = this.state;
			final Stream active = snapshot.stream();
			boolean rendered = false;
			try {
				if (connection != null && connection.authenticated && !connection.closed) {
					// 1. 配置同步：代次变化时先切换请求额度的代次（旧代次的请求随之作废），再发送新的 CONFIG。
					final int gen = snapshot.generation();
					if (connection.configuredGeneration() != gen) {
						connection.resetCredit(gen);
						connection.write(AppleBridgeProtocol.encodeConfig(buildConfig(snapshot)));
						lastAudioNanos = now;
					}
					// 2. 头部姿态：约 100 Hz。
					if (now - lastHeadNanos >= HEAD_INTERVAL_NANOS) {
						lastHeadNanos = now;
						final Quat head = this.sampleHead();
						this.lastHead = head == null ? Quat.IDENTITY : head;
						connection.write(AppleBridgeProtocol.encodeHead(headBuffer, head));
					}
					// 3. 音频：按助手请求渲染。
					if (active != null) {
						final int frames = connection.takeCredit(gen, MAX_CHUNK_FRAMES);
						if (frames > 0) {
							final int bytes = AppleBridgeProtocol.HEADER_SIZE + AppleBridgeProtocol.AUDIO_PREFIX_SIZE
								+ frames * active.channels() * Float.BYTES;
							if (audio.capacity() < bytes) {
								audio = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
							}
							if (audioBytes.length < bytes) {
								audioBytes = new byte[bytes];
							}
							AppleBridgeProtocol.writeAudioPrefix(audio, gen, frames, active.channels());
							if (this.renderInto(active, audio, frames)) {
								audio.flip();
								audio.get(audioBytes, 0, bytes);
								connection.write(audioBytes, bytes);
								this.framesSent.addAndGet(frames);
								lastAudioNanos = now;
								freeRunDeadline = now;
								rendered = true;
							}
						} else if (now - lastAudioNanos > STALL_NANOS) {
							// 助手暂停消费（例如正在切换输出设备）：空转推进，避免游戏声音堆积。
							audio = this.ensureCapacity(audio, active);
							freeRunDeadline = this.freeRun(active, audio, now, freeRunDeadline);
						}
					}
				} else if (active != null) {
					// 未连接：以实时速度空转渲染并丢弃。
					audio = this.ensureCapacity(audio, active);
					freeRunDeadline = this.freeRun(active, audio, now, freeRunDeadline);
				}
			} catch (IOException e) {
				if (connection != null) {
					connection.close();
					if (this.current == connection) {
						this.current = null;
					}
				}
			} catch (RuntimeException e) {
				SpatialAudio.LOGGER.error("Apple 音频桥泵线程出错", e);
				LockSupport.parkNanos(50_000_000L);
			}
			if (!rendered) {
				// 既没有连接也没有声场时无事可做，放慢轮询以节省 CPU。
				LockSupport.parkNanos(connection == null && active == null ? IDLE_PARK_NANOS : PUMP_PARK_NANOS);
			}
		}
	}

	/**
	 * 确保音频缓冲区至少能容纳一个空转块。
	 *
	 * @param audio 现有缓冲区
	 * @param active 声场
	 * @return 容量足够的缓冲区
	 */
	private ByteBuffer ensureCapacity(final ByteBuffer audio, final Stream active) {
		final int bytes = AppleBridgeProtocol.HEADER_SIZE + AppleBridgeProtocol.AUDIO_PREFIX_SIZE
			+ Math.max(FREE_RUN_FRAMES, MAX_CHUNK_FRAMES) * active.channels() * Float.BYTES;
		return audio.capacity() >= bytes ? audio : ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
	}

	/**
	 * 空转渲染：按实时速度渲染并丢弃，使游戏中的声音照常播放完毕。
	 *
	 * @param active 声场
	 * @param audio 临时缓冲区
	 * @param now 当前时刻
	 * @param deadline 下一块的计划时刻
	 * @return 新的计划时刻
	 */
	private long freeRun(final Stream active, final ByteBuffer audio, final long now, final long deadline) {
		final long chunkNanos = FREE_RUN_FRAMES * 1_000_000_000L / Math.max(1, active.sampleRate());
		long next = deadline;
		if (now - next > 10 * chunkNanos) {
			// 落后太多（例如刚从休眠恢复）时不再追赶。
			next = now;
		}
		if (audio.capacity() < FREE_RUN_FRAMES * active.channels() * Float.BYTES) {
			return next;
		}
		// 线程唤醒可能比预期晚（系统会合并定时器），因此一次补齐所有到期的块，保证长期速度等于实时。
		while (now >= next) {
			audio.clear();
			if (!this.renderInto(active, audio, FREE_RUN_FRAMES)) {
				return now;
			}
			next += chunkNanos;
		}
		return next;
	}

	/**
	 * 在渲染锁保护下调用渲染源。
	 *
	 * @param active 期望的声场
	 * @param target 目标缓冲区
	 * @param frames 帧数
	 * @return 渲染成功返回 true；声场已被拆除或替换时返回 false
	 */
	private boolean renderInto(final Stream active, final ByteBuffer target, final int frames) {
		this.renderLock.lock();
		try {
			if (this.state.stream() != active) {
				return false;
			}
			final int start = target.position();
			active.source().render(target, frames);
			target.position(start + frames * active.channels() * Float.BYTES);
			return true;
		} finally {
			this.renderLock.unlock();
		}
	}

	/**
	 * 根据状态快照构建 CONFIG。
	 *
	 * @param snapshot 状态快照
	 * @return 配置
	 */
	private static AppleBridgeProtocol.Config buildConfig(final State snapshot) {
		final int gen = snapshot.generation();
		final Stream active = snapshot.stream();
		final Settings current = snapshot.settings();
		final int flags = current.preferAutoHeadTracking() ? AppleBridgeProtocol.CONFIG_PREFER_AUTO_HEAD_TRACKING : 0;
		if (active == null) {
			return new AppleBridgeProtocol.Config(gen, 48000, 0, AppleBridgeProtocol.LAYOUT_NONE, flags, current.targetFrames(), "");
		}
		return new AppleBridgeProtocol.Config(gen, active.sampleRate(), active.channels(), active.layout(), flags,
			current.targetFrames(), active.deviceName() == null ? "" : active.deviceName());
	}

	/**
	 * 读取头部姿态，来源出错时返回 null。
	 *
	 * @return 头部旋转
	 */
	private Quat sampleHead() {
		try {
			return this.headSupplier.get();
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ===================== 工具 =====================

	/**
	 * 生成随机令牌。
	 *
	 * @return 32 位小写十六进制字符
	 */
	private static String newToken() {
		final byte[] bytes = new byte[AppleBridgeProtocol.TOKEN_LENGTH / 2];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	/**
	 * 以恒定时间比较两个字符串，避免通过响应时间猜测令牌。
	 *
	 * @param a 字符串 a
	 * @param b 字符串 b
	 * @return 相等时返回 true
	 */
	private static boolean constantTimeEquals(final String a, final String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * 一个助手连接。
	 */
	private static final class Connection {
		/** 套接字。 */
		final Socket socket;

		/** 输出流（只由泵线程写入）。 */
		private final OutputStream out;

		/** 是否已通过令牌校验。 */
		volatile boolean authenticated;

		/** 是否已关闭。 */
		volatile boolean closed;

		/**
		 * 请求额度：高 32 位为已发送给该连接的配置代次（0 表示尚未发送），低 32 位为助手已请求但尚未发送的帧数。
		 * 两者打包成一个原子值，保证旧代次的请求不会在切换代次的瞬间被计入新代次。
		 */
		private final AtomicLong credit = new AtomicLong();

		/** 当前写入开始的时刻（纳秒）；0 表示没有正在进行的写入。 */
		private volatile long writeStartedNanos;

		/**
		 * 包装套接字。
		 *
		 * @param socket 套接字
		 * @throws IOException 无法获取输出流
		 */
		Connection(final Socket socket) throws IOException {
			this.socket = socket;
			this.out = socket.getOutputStream();
		}

		/**
		 * 已发送给该连接的配置代次。
		 *
		 * @return 代次；尚未发送配置时为 0
		 */
		int configuredGeneration() {
			return (int) (this.credit.get() >>> 32);
		}

		/**
		 * 切换到新的配置代次并清空请求额度。
		 *
		 * @param generation 新代次
		 */
		void resetCredit(final int generation) {
			this.credit.set(pack(generation, 0L));
		}

		/**
		 * 累加请求帧数（带上限）；代次与当前配置不一致的请求被忽略。
		 *
		 * @param generation 请求所属的代次
		 * @param frames 帧数
		 * @return 计入额度时返回 true
		 */
		boolean addCredit(final int generation, final int frames) {
			while (true) {
				final long value = this.credit.get();
				if ((int) (value >>> 32) != generation || generation == 0) {
					return false;
				}
				final long next = Math.min(MAX_CREDIT_FRAMES, (value & 0xFFFFFFFFL) + frames);
				if (this.credit.compareAndSet(value, pack(generation, next))) {
					return true;
				}
			}
		}

		/**
		 * 取出指定代次最多 max 帧的请求额度。
		 *
		 * @param generation 代次
		 * @param max 上限
		 * @return 实际取出的帧数
		 */
		int takeCredit(final int generation, final int max) {
			while (true) {
				final long value = this.credit.get();
				if ((int) (value >>> 32) != generation) {
					return 0;
				}
				final long available = value & 0xFFFFFFFFL;
				final long take = Math.min(available, max);
				if (take <= 0L || this.credit.compareAndSet(value, pack(generation, available - take))) {
					return (int) Math.max(0L, take);
				}
			}
		}

		/**
		 * 当前写入已阻塞的时长。
		 *
		 * @param now 当前时刻
		 * @return 纳秒；没有正在进行的写入时为 0
		 */
		long writeBlockedNanos(final long now) {
			final long started = this.writeStartedNanos;
			return started == 0L ? 0L : now - started;
		}

		/**
		 * 把代次与帧数打包为一个 long。
		 *
		 * @param generation 代次
		 * @param frames 帧数
		 * @return 打包值
		 */
		private static long pack(final int generation, final long frames) {
			return (long) generation << 32 | frames & 0xFFFFFFFFL;
		}

		/**
		 * 发送一条已编码的消息（堆缓冲区）。
		 *
		 * @param message 消息
		 * @throws IOException 写入失败
		 */
		void write(final ByteBuffer message) throws IOException {
			if (message.hasArray()) {
				this.write(message.array(), message.arrayOffset() + message.position(), message.remaining());
			} else {
				final byte[] bytes = new byte[message.remaining()];
				message.get(bytes);
				this.write(bytes, bytes.length);
			}
		}

		/**
		 * 发送字节数组的前 length 个字节。
		 *
		 * @param bytes 数据
		 * @param length 长度
		 * @throws IOException 写入失败
		 */
		void write(final byte[] bytes, final int length) throws IOException {
			this.write(bytes, 0, length);
		}

		/**
		 * 发送字节数组的一段。
		 *
		 * @param bytes 数据
		 * @param offset 起点
		 * @param length 长度
		 * @throws IOException 写入失败
		 */
		private void write(final byte[] bytes, final int offset, final int length) throws IOException {
			if (this.closed) {
				throw new SocketException("连接已关闭");
			}
			this.writeStartedNanos = System.nanoTime();
			try {
				this.out.write(bytes, offset, length);
			} finally {
				this.writeStartedNanos = 0L;
			}
		}

		/**
		 * 关闭连接（可重复调用）。
		 */
		void close() {
			this.closed = true;
			try {
				this.socket.close();
			} catch (IOException ignored) {
				// 关闭失败不影响后续流程。
			}
		}
	}
}
