package io.github.obsidianplusplus.spatialaudio.apple;

import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * SAAB（SpatialAudio Audio Bridge）协议：模组与 macOS 助手之间传输多声道声场、头部姿态与渲染状态的本机 TCP 协议。
 *
 * <p>模组监听 127.0.0.1 上的临时端口，助手主动连接并先发送 HELLO 完成令牌校验。所有字段均为小端序，
 * 每条消息由 12 字节消息头（魔数 {@code SAAB}、类型、保留字段、负载长度）与负载组成。
 * 协议定义同时存在于 Swift 助手（{@code native/macos/SpatialAudioHeadTracker/main.swift}），修改时必须保持一致。
 *
 * <p>本类只包含常量与纯函数（不涉及套接字），便于单元测试。
 */
public final class AppleBridgeProtocol {
	/** 魔数 "SAAB" 按小端序读取得到的整数。 */
	public static final int MAGIC = 0x42414153;

	/** 协议版本。 */
	public static final int VERSION = 1;

	/** 消息头长度（字节）。 */
	public static final int HEADER_SIZE = 12;

	/** 负载长度上限（字节），超出视为协议错误。 */
	public static final int MAX_PAYLOAD = 4 * 1024 * 1024;

	/** 令牌长度（字符）。 */
	public static final int TOKEN_LENGTH = 32;

	/** 消息类型：助手问候。 */
	public static final int TYPE_HELLO = 1;

	/** 消息类型：声场配置。 */
	public static final int TYPE_CONFIG = 2;

	/** 消息类型：音频数据。 */
	public static final int TYPE_AUDIO = 3;

	/** 消息类型：助手请求音频帧。 */
	public static final int TYPE_REQUEST = 4;

	/** 消息类型：头部姿态。 */
	public static final int TYPE_HEAD = 5;

	/** 消息类型：助手状态。 */
	public static final int TYPE_STATUS = 6;

	/** HELLO 负载长度（字节）。 */
	public static final int HELLO_SIZE = 48;

	/** HEAD 负载长度（字节）。 */
	public static final int HEAD_SIZE = 20;

	/** AUDIO 负载中样本之前的字段长度（字节）。 */
	public static final int AUDIO_PREFIX_SIZE = 8;

	/** 能力位：助手可以使用 Apple 自动头部追踪（macOS 15+ 且具备 head-pose 授权）。 */
	public static final int CAP_AUTO_HEAD_TRACKING = 1;

	/** 能力位：助手具备个性化空间音频授权（系统会自动使用用户的个性化 HRTF）。 */
	public static final int CAP_PERSONALIZED_PROFILE = 1 << 1;

	/** 能力位：系统支持耳机运动数据。 */
	public static final int CAP_MOTION_AVAILABLE = 1 << 2;

	/** 声场布局：空闲（当前没有声场）。 */
	public static final int LAYOUT_NONE = 0;

	/** 声场布局：Ambisonics（ACN 顺序、SN3D 归一化）。 */
	public static final int LAYOUT_HOA_ACN_SN3D = 1;

	/** 声场布局：7.1，顺序 FL FR FC LFE BL BR SL SR。 */
	public static final int LAYOUT_SURROUND_7_1 = 2;

	/** CONFIG 标志位：优先使用 Apple 自动头部追踪。 */
	public static final int CONFIG_PREFER_AUTO_HEAD_TRACKING = 1;

	/** HEAD 标志位：姿态有效。 */
	public static final int HEAD_VALID = 1;

	/** STATUS 标志位：音频引擎正在运行。 */
	public static final int STATUS_ENGINE_RUNNING = 1;

	/** STATUS 标志位：Apple 自动头部追踪生效中。 */
	public static final int STATUS_AUTO_HEAD_TRACKING_ACTIVE = 1 << 1;

	/** STATUS 标志位：输出设备推测为耳机。 */
	public static final int STATUS_HEADPHONES = 1 << 2;

	/** STATUS 标志位：声场已作为环境声床接入 Apple 渲染引擎。 */
	public static final int STATUS_BED_ACCEPTED = 1 << 3;

	/** STATUS 标志位：音频引擎出错。 */
	public static final int STATUS_ENGINE_ERROR = 1 << 4;

	private AppleBridgeProtocol() {
	}

	/**
	 * 消息头。
	 *
	 * @param type 消息类型
	 * @param length 负载长度
	 */
	public record Header(int type, int length) {
	}

	/**
	 * 助手问候。
	 *
	 * @param version 协议版本
	 * @param token 令牌
	 * @param capabilities 能力位
	 * @param macOsMajor macOS 主版本号
	 * @param helperPid 助手进程号
	 */
	public record Hello(int version, String token, int capabilities, int macOsMajor, int helperPid) {
		/**
		 * 是否具备某项能力。
		 *
		 * @param flag 能力位
		 * @return 具备时返回 true
		 */
		public boolean has(final int flag) {
			return (this.capabilities & flag) != 0;
		}
	}

	/**
	 * 声场配置。
	 *
	 * @param generation 配置代次
	 * @param sampleRate 采样率
	 * @param channels 声道数（0 表示空闲）
	 * @param layout 声场布局
	 * @param flags 标志位
	 * @param targetFrames 助手应保持的缓冲帧数
	 * @param deviceName 输出设备名称，空字符串表示系统默认设备
	 */
	public record Config(int generation, int sampleRate, int channels, int layout, int flags, int targetFrames, String deviceName) {
	}

	/**
	 * 助手请求音频帧。
	 *
	 * @param generation 配置代次
	 * @param frames 帧数
	 */
	public record Request(int generation, int frames) {
	}

	/**
	 * 助手状态。
	 *
	 * @param flags 状态标志位
	 * @param generation 助手正在使用的配置代次
	 * @param bufferedFrames 环形缓冲中的帧数
	 * @param underruns 累计欠载次数
	 * @param renderingAlgorithm 实际使用的渲染算法原始值（7 为自动，即 Apple 空间音频）
	 * @param outputSampleRate 输出硬件采样率
	 * @param outputLatency 输出延迟（秒）
	 * @param deviceName 输出设备名称
	 * @param error 最近一次错误信息
	 */
	public record Status(int flags, int generation, int bufferedFrames, int underruns, int renderingAlgorithm,
		int outputSampleRate, float outputLatency, String deviceName, String error) {
		/** 尚未收到状态时的占位值。 */
		public static final Status NONE = new Status(0, 0, 0, 0, 0, 0, 0.0f, "", "");

		/**
		 * 是否包含某个标志位。
		 *
		 * @param flag 标志位
		 * @return 包含时返回 true
		 */
		public boolean has(final int flag) {
			return (this.flags & flag) != 0;
		}
	}

	/**
	 * 解析消息头。
	 *
	 * @param buffer 至少包含 12 字节的缓冲区（从当前位置读取，读取后位置前进 12 字节）
	 * @return 消息头
	 * @throws ProtocolException 魔数错误或负载过长
	 */
	public static Header readHeader(final ByteBuffer buffer) throws ProtocolException {
		final ByteBuffer b = buffer.order(ByteOrder.LITTLE_ENDIAN);
		final int magic = b.getInt();
		final int type = Short.toUnsignedInt(b.getShort());
		b.getShort();
		final int length = b.getInt();
		if (magic != MAGIC) {
			throw new ProtocolException("魔数错误：0x" + Integer.toHexString(magic));
		}
		if (length < 0 || length > MAX_PAYLOAD) {
			throw new ProtocolException("负载长度非法：" + Integer.toUnsignedString(length));
		}
		return new Header(type, length);
	}

	/**
	 * 写入消息头。
	 *
	 * @param buffer 目标缓冲区（小端序，从当前位置写入）
	 * @param type 消息类型
	 * @param length 负载长度
	 */
	public static void writeHeader(final ByteBuffer buffer, final int type, final int length) {
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(MAGIC);
		buffer.putShort((short) type);
		buffer.putShort((short) 0);
		buffer.putInt(length);
	}

	/**
	 * 编码 HELLO 消息（模组只需解码；编码用于测试与模拟助手）。
	 *
	 * @param hello 问候内容
	 * @return 含消息头的完整消息
	 */
	public static ByteBuffer encodeHello(final Hello hello) {
		final ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + HELLO_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		writeHeader(b, TYPE_HELLO, HELLO_SIZE);
		b.putInt(hello.version());
		final byte[] token = new byte[TOKEN_LENGTH];
		final byte[] raw = hello.token().getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(raw, 0, token, 0, Math.min(raw.length, TOKEN_LENGTH));
		b.put(token);
		b.putInt(hello.capabilities());
		b.putInt(hello.macOsMajor());
		b.putInt(hello.helperPid());
		return b.flip();
	}

	/**
	 * 解码 HELLO 负载。
	 *
	 * @param payload 负载
	 * @return 问候内容
	 * @throws ProtocolException 长度不足
	 */
	public static Hello decodeHello(final ByteBuffer payload) throws ProtocolException {
		requireRemaining(payload, HELLO_SIZE, "HELLO");
		final ByteBuffer b = payload.order(ByteOrder.LITTLE_ENDIAN);
		final int version = b.getInt();
		final byte[] token = new byte[TOKEN_LENGTH];
		b.get(token);
		return new Hello(version, new String(token, StandardCharsets.US_ASCII), b.getInt(), b.getInt(), b.getInt());
	}

	/**
	 * 编码 CONFIG 消息。
	 *
	 * @param config 配置
	 * @return 含消息头的完整消息
	 */
	public static ByteBuffer encodeConfig(final Config config) {
		final byte[] name = utf8(config.deviceName(), 1024);
		final int length = 22 + name.length;
		final ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + length).order(ByteOrder.LITTLE_ENDIAN);
		writeHeader(b, TYPE_CONFIG, length);
		b.putInt(config.generation());
		b.putInt(config.sampleRate());
		b.putShort((short) config.channels());
		b.putShort((short) config.layout());
		b.putInt(config.flags());
		b.putInt(config.targetFrames());
		b.putShort((short) name.length);
		b.put(name);
		return b.flip();
	}

	/**
	 * 解码 CONFIG 负载（用于测试与模拟助手）。
	 *
	 * @param payload 负载
	 * @return 配置
	 * @throws ProtocolException 长度不足
	 */
	public static Config decodeConfig(final ByteBuffer payload) throws ProtocolException {
		requireRemaining(payload, 22, "CONFIG");
		final ByteBuffer b = payload.order(ByteOrder.LITTLE_ENDIAN);
		final int generation = b.getInt();
		final int sampleRate = b.getInt();
		final int channels = Short.toUnsignedInt(b.getShort());
		final int layout = Short.toUnsignedInt(b.getShort());
		final int flags = b.getInt();
		final int target = b.getInt();
		final String name = readString(b, "CONFIG");
		return new Config(generation, sampleRate, channels, layout, flags, target, name);
	}

	/**
	 * 编码 REQUEST 消息（用于测试与模拟助手）。
	 *
	 * @param request 请求
	 * @return 含消息头的完整消息
	 */
	public static ByteBuffer encodeRequest(final Request request) {
		final ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + 8).order(ByteOrder.LITTLE_ENDIAN);
		writeHeader(b, TYPE_REQUEST, 8);
		b.putInt(request.generation());
		b.putInt(request.frames());
		return b.flip();
	}

	/**
	 * 解码 REQUEST 负载。
	 *
	 * @param payload 负载
	 * @return 请求
	 * @throws ProtocolException 长度不足或帧数为负
	 */
	public static Request decodeRequest(final ByteBuffer payload) throws ProtocolException {
		requireRemaining(payload, 8, "REQUEST");
		final ByteBuffer b = payload.order(ByteOrder.LITTLE_ENDIAN);
		final Request request = new Request(b.getInt(), b.getInt());
		if (request.frames() < 0) {
			throw new ProtocolException("REQUEST 帧数非法：" + Integer.toUnsignedString(request.frames()));
		}
		return request;
	}

	/**
	 * 写入 HEAD 消息。
	 *
	 * @param buffer 目标缓冲区（容量至少 32 字节，写入后已翻转可直接发送）
	 * @param rotation 头部旋转；为 null 时写入“无效”姿态
	 * @return 同一缓冲区
	 */
	public static ByteBuffer encodeHead(final ByteBuffer buffer, final Quat rotation) {
		buffer.clear().order(ByteOrder.LITTLE_ENDIAN);
		writeHeader(buffer, TYPE_HEAD, HEAD_SIZE);
		final boolean valid = rotation != null && rotation.isFinite();
		final Quat q = valid ? rotation.normalize() : Quat.IDENTITY;
		buffer.putInt(valid ? HEAD_VALID : 0);
		buffer.putFloat((float) q.x());
		buffer.putFloat((float) q.y());
		buffer.putFloat((float) q.z());
		buffer.putFloat((float) q.w());
		return buffer.flip();
	}

	/**
	 * 解码 HEAD 负载（用于测试与模拟助手）。
	 *
	 * @param payload 负载
	 * @return 头部旋转；无效姿态返回 null
	 * @throws ProtocolException 长度不足
	 */
	public static Quat decodeHead(final ByteBuffer payload) throws ProtocolException {
		requireRemaining(payload, HEAD_SIZE, "HEAD");
		final ByteBuffer b = payload.order(ByteOrder.LITTLE_ENDIAN);
		final int flags = b.getInt();
		final Quat q = new Quat(b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat());
		return (flags & HEAD_VALID) != 0 ? q : null;
	}

	/**
	 * 编码 STATUS 消息（用于测试与模拟助手）。
	 *
	 * @param status 状态
	 * @return 含消息头的完整消息
	 */
	public static ByteBuffer encodeStatus(final Status status) {
		final byte[] name = utf8(status.deviceName(), 1024);
		final byte[] error = utf8(status.error(), 2048);
		final int length = 28 + 2 + name.length + 2 + error.length;
		final ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + length).order(ByteOrder.LITTLE_ENDIAN);
		writeHeader(b, TYPE_STATUS, length);
		b.putInt(status.flags());
		b.putInt(status.generation());
		b.putInt(status.bufferedFrames());
		b.putInt(status.underruns());
		b.putInt(status.renderingAlgorithm());
		b.putInt(status.outputSampleRate());
		b.putFloat(status.outputLatency());
		b.putShort((short) name.length);
		b.put(name);
		b.putShort((short) error.length);
		b.put(error);
		return b.flip();
	}

	/**
	 * 解码 STATUS 负载。
	 *
	 * @param payload 负载
	 * @return 状态
	 * @throws ProtocolException 长度不足
	 */
	public static Status decodeStatus(final ByteBuffer payload) throws ProtocolException {
		requireRemaining(payload, 32, "STATUS");
		final ByteBuffer b = payload.order(ByteOrder.LITTLE_ENDIAN);
		final int flags = b.getInt();
		final int generation = b.getInt();
		final int buffered = b.getInt();
		final int underruns = b.getInt();
		final int algorithm = b.getInt();
		final int rate = b.getInt();
		final float latency = b.getFloat();
		final String device = readString(b, "STATUS");
		final String error = readString(b, "STATUS");
		return new Status(flags, generation, buffered, underruns, algorithm, rate, Float.isFinite(latency) ? latency : 0.0f, device, error);
	}

	/**
	 * 写入 AUDIO 消息头与前缀（样本由调用方随后写入）。
	 *
	 * @param buffer 目标缓冲区（从位置 0 写入）
	 * @param generation 配置代次
	 * @param frames 帧数
	 * @param channels 声道数
	 */
	public static void writeAudioPrefix(final ByteBuffer buffer, final int generation, final int frames, final int channels) {
		buffer.clear().order(ByteOrder.LITTLE_ENDIAN);
		writeHeader(buffer, TYPE_AUDIO, AUDIO_PREFIX_SIZE + frames * channels * Float.BYTES);
		buffer.putInt(generation);
		buffer.putInt(frames);
	}

	/**
	 * 按声场格式计算 CONFIG 中的布局编号。
	 *
	 * @param ambisonic 是否为 Ambisonics
	 * @return 布局编号
	 */
	public static int layoutFor(final boolean ambisonic) {
		return ambisonic ? LAYOUT_HOA_ACN_SN3D : LAYOUT_SURROUND_7_1;
	}

	/**
	 * 校验缓冲区剩余字节数。
	 *
	 * @param buffer 缓冲区
	 * @param size 需要的字节数
	 * @param what 消息名（用于错误信息）
	 * @throws ProtocolException 长度不足
	 */
	private static void requireRemaining(final ByteBuffer buffer, final int size, final String what) throws ProtocolException {
		if (buffer.remaining() < size) {
			throw new ProtocolException(what + " 负载过短：" + buffer.remaining() + " < " + size);
		}
	}

	/**
	 * 读取“u16 长度 + UTF-8 字节”格式的字符串。
	 *
	 * @param b 缓冲区
	 * @param what 消息名（用于错误信息）
	 * @return 字符串
	 * @throws ProtocolException 长度不足
	 */
	private static String readString(final ByteBuffer b, final String what) throws ProtocolException {
		requireRemaining(b, 2, what);
		final int length = Short.toUnsignedInt(b.getShort());
		requireRemaining(b, length, what);
		final byte[] bytes = new byte[length];
		b.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * 把字符串编码为 UTF-8 并按字节上限截断（在字符边界截断）。
	 *
	 * @param text 字符串，可为 null
	 * @param limit 字节上限
	 * @return 字节数组
	 */
	private static byte[] utf8(final String text, final int limit) {
		if (text == null || text.isEmpty()) {
			return new byte[0];
		}
		String value = text;
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		while (bytes.length > limit && !value.isEmpty()) {
			value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
			bytes = value.getBytes(StandardCharsets.UTF_8);
		}
		return bytes;
	}

	/**
	 * 协议格式错误。
	 */
	public static final class ProtocolException extends Exception {
		/** 序列化版本号。 */
		private static final long serialVersionUID = 1L;

		/**
		 * 创建异常。
		 *
		 * @param message 错误信息
		 */
		public ProtocolException(final String message) {
			super(message);
		}
	}
}
