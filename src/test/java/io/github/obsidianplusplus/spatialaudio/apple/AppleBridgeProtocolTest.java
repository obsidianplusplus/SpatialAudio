package io.github.obsidianplusplus.spatialaudio.apple;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * SAAB 协议编解码测试：往返一致性、消息头校验、负载长度校验，以及与设计文档（SPEC §3）逐字节一致的字段偏移。
 */
class AppleBridgeProtocolTest {
	/** 测试用令牌（32 个小写十六进制字符）。 */
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	/** 浮点比较容差（协议中的四元数为 32 位浮点）。 */
	private static final double FLOAT_EPS = 1.0e-6;

	/**
	 * 魔数按小端序存放时，前四个字节应为 ASCII "SAAB"。
	 */
	@Test
	void magicIsAsciiSaab() {
		final ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(AppleBridgeProtocol.MAGIC);
		assertArrayEquals("SAAB".getBytes(StandardCharsets.US_ASCII), b.array());
		assertEquals(AppleBridgeProtocol.LAYOUT_HOA_ACN_SN3D, AppleBridgeProtocol.layoutFor(true));
		assertEquals(AppleBridgeProtocol.LAYOUT_SURROUND_7_1, AppleBridgeProtocol.layoutFor(false));
	}

	/**
	 * HELLO 往返解析，并逐字节检查消息头与负载布局（版本 0、令牌 4、能力位 36、系统版本 40、进程号 44）。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void helloRoundTripAndLayout() throws Exception {
		final int caps = AppleBridgeProtocol.CAP_AUTO_HEAD_TRACKING | AppleBridgeProtocol.CAP_MOTION_AVAILABLE;
		final AppleBridgeProtocol.Hello hello = new AppleBridgeProtocol.Hello(AppleBridgeProtocol.VERSION, TOKEN, caps, 15, 4321);
		final ByteBuffer message = AppleBridgeProtocol.encodeHello(hello);
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + AppleBridgeProtocol.HELLO_SIZE, message.remaining());
		final byte[] bytes = toBytes(message);
		assertArrayEquals(new byte[] {'S', 'A', 'A', 'B'}, new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]});
		final ByteBuffer raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(AppleBridgeProtocol.TYPE_HELLO, raw.getShort(4));
		assertEquals(0, raw.getShort(6));
		assertEquals(AppleBridgeProtocol.HELLO_SIZE, raw.getInt(8));

		final ByteBuffer payload = payloadOf(bytes);
		assertEquals(AppleBridgeProtocol.VERSION, payload.getInt(0));
		assertEquals(TOKEN, new String(bytes, AppleBridgeProtocol.HEADER_SIZE + 4, AppleBridgeProtocol.TOKEN_LENGTH, StandardCharsets.US_ASCII));
		assertEquals(caps, payload.getInt(36));
		assertEquals(15, payload.getInt(40));
		assertEquals(4321, payload.getInt(44));

		final ByteBuffer whole = ByteBuffer.wrap(bytes);
		final AppleBridgeProtocol.Header header = AppleBridgeProtocol.readHeader(whole);
		assertEquals(new AppleBridgeProtocol.Header(AppleBridgeProtocol.TYPE_HELLO, AppleBridgeProtocol.HELLO_SIZE), header);
		assertEquals(AppleBridgeProtocol.HEADER_SIZE, whole.position());
		final AppleBridgeProtocol.Hello decoded = AppleBridgeProtocol.decodeHello(whole);
		assertEquals(hello, decoded);
		assertTrue(decoded.has(AppleBridgeProtocol.CAP_AUTO_HEAD_TRACKING));
		assertTrue(decoded.has(AppleBridgeProtocol.CAP_MOTION_AVAILABLE));
		assertFalse(decoded.has(AppleBridgeProtocol.CAP_PERSONALIZED_PROFILE));
	}

	/**
	 * 过长的令牌被截断为 32 字节，过短的令牌以 0 补齐（因此不可能与真实令牌相等）。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void helloTokenIsFixedWidth() throws Exception {
		final ByteBuffer longToken = AppleBridgeProtocol.encodeHello(new AppleBridgeProtocol.Hello(1, TOKEN + "ffff", 0, 14, 1));
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + AppleBridgeProtocol.HELLO_SIZE, longToken.remaining());
		assertEquals(TOKEN, AppleBridgeProtocol.decodeHello(payloadOf(toBytes(longToken))).token());

		final byte[] shortBytes = toBytes(AppleBridgeProtocol.encodeHello(new AppleBridgeProtocol.Hello(1, "abc", 0, 14, 1)));
		final String shortToken = AppleBridgeProtocol.decodeHello(payloadOf(shortBytes)).token();
		assertEquals(AppleBridgeProtocol.TOKEN_LENGTH, shortToken.length());
		assertTrue(shortToken.startsWith("abc"));
		assertEquals(0, shortBytes[AppleBridgeProtocol.HEADER_SIZE + 4 + 3]);
		assertFalse(shortToken.equals("abc"));
	}

	/**
	 * CONFIG 往返解析（含多字节 UTF-8 设备名），并检查 SPEC 中的字段偏移：声道数位于负载偏移 8、布局 10、名称长度 20、名称 22。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void configRoundTripAndLayout() throws Exception {
		final String device = "小明的 AirPods Pro – 耳机 🎧";
		final byte[] deviceBytes = device.getBytes(StandardCharsets.UTF_8);
		final AppleBridgeProtocol.Config config = new AppleBridgeProtocol.Config(7, 48000, 16, AppleBridgeProtocol.LAYOUT_HOA_ACN_SN3D,
			AppleBridgeProtocol.CONFIG_PREFER_AUTO_HEAD_TRACKING, 1440, device);
		final byte[] bytes = toBytes(AppleBridgeProtocol.encodeConfig(config));
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + 22 + deviceBytes.length, bytes.length);
		final ByteBuffer raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(AppleBridgeProtocol.TYPE_CONFIG, raw.getShort(4));
		assertEquals(22 + deviceBytes.length, raw.getInt(8));

		final ByteBuffer payload = payloadOf(bytes);
		assertEquals(7, payload.getInt(0));
		assertEquals(48000, payload.getInt(4));
		assertEquals(16, payload.getShort(8));
		assertEquals(AppleBridgeProtocol.LAYOUT_HOA_ACN_SN3D, payload.getShort(10));
		assertEquals(AppleBridgeProtocol.CONFIG_PREFER_AUTO_HEAD_TRACKING, payload.getInt(12));
		assertEquals(1440, payload.getInt(16));
		assertEquals(deviceBytes.length, payload.getShort(20));
		final byte[] name = new byte[deviceBytes.length];
		payload.get(22, name);
		assertArrayEquals(deviceBytes, name);

		assertEquals(config, AppleBridgeProtocol.decodeConfig(payloadOf(bytes)));
	}

	/**
	 * 空闲配置（0 声道）与空设备名（null 视为空字符串）。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void idleConfigWithoutDevice() throws Exception {
		final AppleBridgeProtocol.Config idle = new AppleBridgeProtocol.Config(3, 48000, 0, AppleBridgeProtocol.LAYOUT_NONE, 0, 480, null);
		final byte[] bytes = toBytes(AppleBridgeProtocol.encodeConfig(idle));
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + 22, bytes.length);
		final AppleBridgeProtocol.Config decoded = AppleBridgeProtocol.decodeConfig(payloadOf(bytes));
		assertEquals(0, decoded.channels());
		assertEquals(AppleBridgeProtocol.LAYOUT_NONE, decoded.layout());
		assertEquals("", decoded.deviceName());
		assertEquals(480, decoded.targetFrames());
	}

	/**
	 * 超长设备名按 1024 字节上限在字符边界截断，解码后仍是合法的 UTF-8 前缀。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void configTruncatesLongDeviceNames() throws Exception {
		// 每个汉字 3 字节：600 个汉字共 1800 字节，最多保留 341 个（1023 字节）。
		final String chinese = "耳".repeat(600);
		final AppleBridgeProtocol.Config decodedChinese = roundTripConfig(chinese);
		assertEquals("耳".repeat(341), decodedChinese.deviceName());

		// 每个表情 4 字节（UTF-16 代理对）：1 + 4 × 255 = 1021 字节，绝不能把代理对拆开。
		final String emoji = "a" + "🎧".repeat(300);
		final AppleBridgeProtocol.Config decodedEmoji = roundTripConfig(emoji);
		assertEquals("a" + "🎧".repeat(255), decodedEmoji.deviceName());
		assertFalse(decodedEmoji.deviceName().contains("�"));

		// 恰好 1024 字节的名称原样保留。
		final String exact = "x".repeat(1024);
		assertEquals(exact, roundTripConfig(exact).deviceName());
	}

	/**
	 * REQUEST 往返解析与布局；帧数按无符号解释超过 2^31 时视为非法。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void requestRoundTripAndValidation() throws Exception {
		final byte[] bytes = toBytes(AppleBridgeProtocol.encodeRequest(new AppleBridgeProtocol.Request(9, 480)));
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + 8, bytes.length);
		final ByteBuffer raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(AppleBridgeProtocol.TYPE_REQUEST, raw.getShort(4));
		assertEquals(8, raw.getInt(8));
		assertEquals(9, raw.getInt(12));
		assertEquals(480, raw.getInt(16));
		assertEquals(new AppleBridgeProtocol.Request(9, 480), AppleBridgeProtocol.decodeRequest(payloadOf(bytes)));

		final ByteBuffer negative = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(9).putInt(-1).flip();
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.decodeRequest(negative));
	}

	/**
	 * HEAD：有效姿态归一化后写入（标志位 0、x 4、y 8、z 12、w 16），null 与非有限值写入“无效 + 单位四元数”。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void headValidAndInvalid() throws Exception {
		final ByteBuffer buffer = ByteBuffer.allocate(AppleBridgeProtocol.HEADER_SIZE + AppleBridgeProtocol.HEAD_SIZE);
		final Quat rotation = Quat.fromEulerZXY(Math.toRadians(90.0), Math.toRadians(10.0), Math.toRadians(-5.0));
		// 故意传入未归一化的四元数，编码时应先归一化。
		final Quat scaled = new Quat(rotation.x() * 2.0, rotation.y() * 2.0, rotation.z() * 2.0, rotation.w() * 2.0);
		final ByteBuffer message = AppleBridgeProtocol.encodeHead(buffer, scaled);
		assertSame(buffer, message);
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + AppleBridgeProtocol.HEAD_SIZE, message.remaining());
		final byte[] bytes = toBytes(message);
		final ByteBuffer raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(AppleBridgeProtocol.TYPE_HEAD, raw.getShort(4));
		assertEquals(AppleBridgeProtocol.HEAD_SIZE, raw.getInt(8));
		final ByteBuffer payload = payloadOf(bytes);
		assertEquals(AppleBridgeProtocol.HEAD_VALID, payload.getInt(0));
		assertEquals(rotation.x(), payload.getFloat(4), FLOAT_EPS);
		assertEquals(rotation.y(), payload.getFloat(8), FLOAT_EPS);
		assertEquals(rotation.z(), payload.getFloat(12), FLOAT_EPS);
		assertEquals(rotation.w(), payload.getFloat(16), FLOAT_EPS);

		final Quat decoded = AppleBridgeProtocol.decodeHead(payloadOf(bytes));
		assertNotNull(decoded);
		assertEquals(90.0, Math.toDegrees(decoded.toEulerZXY()[0]), 1.0e-3);
		assertEquals(10.0, Math.toDegrees(decoded.toEulerZXY()[1]), 1.0e-3);
		assertEquals(-5.0, Math.toDegrees(decoded.toEulerZXY()[2]), 1.0e-3);

		// 同一缓冲区可重复使用：写入 null 得到无效姿态。
		final byte[] invalid = toBytes(AppleBridgeProtocol.encodeHead(buffer, null));
		final ByteBuffer invalidPayload = payloadOf(invalid);
		assertEquals(0, invalidPayload.getInt(0));
		assertEquals(1.0f, invalidPayload.getFloat(16));
		assertNull(AppleBridgeProtocol.decodeHead(payloadOf(invalid)));

		final byte[] nan = toBytes(AppleBridgeProtocol.encodeHead(buffer, new Quat(Double.NaN, 0.0, 0.0, 1.0)));
		assertNull(AppleBridgeProtocol.decodeHead(payloadOf(nan)));
	}

	/**
	 * STATUS 往返解析与布局：输出延迟位于负载偏移 24，设备名从 28 开始；非有限的延迟解码为 0。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void statusRoundTripAndLayout() throws Exception {
		final int flags = AppleBridgeProtocol.STATUS_ENGINE_RUNNING | AppleBridgeProtocol.STATUS_BED_ACCEPTED
			| AppleBridgeProtocol.STATUS_HEADPHONES;
		final String device = "MacBook Pro 扬声器";
		final String error = "引擎配置变化，正在重建";
		final AppleBridgeProtocol.Status status = new AppleBridgeProtocol.Status(flags, 12, 1440, 3, 7, 44100, 0.0125f, device, error);
		final byte[] bytes = toBytes(AppleBridgeProtocol.encodeStatus(status));
		final byte[] deviceBytes = device.getBytes(StandardCharsets.UTF_8);
		final byte[] errorBytes = error.getBytes(StandardCharsets.UTF_8);
		final int length = 28 + 2 + deviceBytes.length + 2 + errorBytes.length;
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + length, bytes.length);
		final ByteBuffer raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(AppleBridgeProtocol.TYPE_STATUS, raw.getShort(4));
		assertEquals(length, raw.getInt(8));

		final ByteBuffer payload = payloadOf(bytes);
		assertEquals(flags, payload.getInt(0));
		assertEquals(12, payload.getInt(4));
		assertEquals(1440, payload.getInt(8));
		assertEquals(3, payload.getInt(12));
		assertEquals(7, payload.getInt(16));
		assertEquals(44100, payload.getInt(20));
		assertEquals(0.0125f, payload.getFloat(24));
		assertEquals(deviceBytes.length, payload.getShort(28));
		assertEquals(errorBytes.length, payload.getShort(30 + deviceBytes.length));

		final AppleBridgeProtocol.Status decoded = AppleBridgeProtocol.decodeStatus(payloadOf(bytes));
		assertEquals(status, decoded);
		assertTrue(decoded.has(AppleBridgeProtocol.STATUS_ENGINE_RUNNING));
		assertTrue(decoded.has(AppleBridgeProtocol.STATUS_BED_ACCEPTED));
		assertFalse(decoded.has(AppleBridgeProtocol.STATUS_ENGINE_ERROR));
		assertFalse(AppleBridgeProtocol.Status.NONE.has(AppleBridgeProtocol.STATUS_ENGINE_RUNNING));

		final AppleBridgeProtocol.Status nan = new AppleBridgeProtocol.Status(0, 1, 0, 0, 0, 0, Float.NaN, "", "");
		assertEquals(0.0f, AppleBridgeProtocol.decodeStatus(payloadOf(toBytes(AppleBridgeProtocol.encodeStatus(nan)))).outputLatency());
	}

	/**
	 * AUDIO 前缀：消息头长度包含全部样本，代次与帧数紧随其后，写入后位置停在样本起点。
	 */
	@Test
	void audioPrefixLayout() {
		final ByteBuffer buffer = ByteBuffer.allocate(256);
		buffer.position(100);
		AppleBridgeProtocol.writeAudioPrefix(buffer, 5, 3, 16);
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + AppleBridgeProtocol.AUDIO_PREFIX_SIZE, buffer.position());
		final ByteBuffer raw = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(AppleBridgeProtocol.MAGIC, raw.getInt(0));
		assertEquals(AppleBridgeProtocol.TYPE_AUDIO, raw.getShort(4));
		assertEquals(AppleBridgeProtocol.AUDIO_PREFIX_SIZE + 3 * 16 * Float.BYTES, raw.getInt(8));
		assertEquals(5, raw.getInt(12));
		assertEquals(3, raw.getInt(16));
		assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order());
	}

	/**
	 * 消息头校验：魔数错误、负载超过 4 MiB 或按无符号解释为超大值时抛出协议异常；类型按无符号读取；恰好 4 MiB 合法。
	 *
	 * @throws Exception 协议错误
	 */
	@Test
	void headerValidation() throws Exception {
		final ByteBuffer ok = header(AppleBridgeProtocol.MAGIC, 0xFFFF, AppleBridgeProtocol.MAX_PAYLOAD);
		assertEquals(new AppleBridgeProtocol.Header(0xFFFF, AppleBridgeProtocol.MAX_PAYLOAD), AppleBridgeProtocol.readHeader(ok));

		final ByteBuffer badMagic = header(0x42414154, AppleBridgeProtocol.TYPE_HELLO, AppleBridgeProtocol.HELLO_SIZE);
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.readHeader(badMagic));

		final ByteBuffer tooLong = header(AppleBridgeProtocol.MAGIC, AppleBridgeProtocol.TYPE_AUDIO, AppleBridgeProtocol.MAX_PAYLOAD + 1);
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.readHeader(tooLong));

		final ByteBuffer huge = header(AppleBridgeProtocol.MAGIC, AppleBridgeProtocol.TYPE_AUDIO, 0xFFFFFFFF);
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.readHeader(huge));

		// 大端序写入的魔数（字节顺序 "BAAS"）同样被拒绝。
		final ByteBuffer bigEndian = ByteBuffer.allocate(AppleBridgeProtocol.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
		bigEndian.putInt(AppleBridgeProtocol.MAGIC).putShort((short) 1).putShort((short) 0).putInt(48).flip();
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.readHeader(bigEndian));
	}

	/**
	 * 负载过短（包括字符串长度字段声称的字节数超过剩余数据）时抛出协议异常，而不是越界或返回半截数据。
	 */
	@Test
	void shortPayloadsThrow() {
		assertThrows(AppleBridgeProtocol.ProtocolException.class,
			() -> AppleBridgeProtocol.decodeHello(ByteBuffer.allocate(AppleBridgeProtocol.HELLO_SIZE - 1)));
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.decodeConfig(ByteBuffer.allocate(21)));
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.decodeRequest(ByteBuffer.allocate(7)));
		assertThrows(AppleBridgeProtocol.ProtocolException.class,
			() -> AppleBridgeProtocol.decodeHead(ByteBuffer.allocate(AppleBridgeProtocol.HEAD_SIZE - 1)));
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.decodeStatus(ByteBuffer.allocate(31)));

		// CONFIG 的设备名长度声称 10 字节，实际只有 3 字节。
		final ByteBuffer config = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
		config.putInt(1).putInt(48000).putShort((short) 16).putShort((short) 1).putInt(0).putInt(1440).putShort((short) 10);
		config.put(new byte[] {'a', 'b', 'c'}).flip();
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.decodeConfig(config));

		// STATUS 缺少错误信息字段。
		final byte[] full = toBytes(AppleBridgeProtocol.encodeStatus(new AppleBridgeProtocol.Status(1, 2, 3, 4, 5, 6, 0.0f, "dev", "")));
		final ByteBuffer truncated = ByteBuffer.wrap(full, AppleBridgeProtocol.HEADER_SIZE, full.length - AppleBridgeProtocol.HEADER_SIZE - 2)
			.slice().order(ByteOrder.LITTLE_ENDIAN);
		assertThrows(AppleBridgeProtocol.ProtocolException.class, () -> AppleBridgeProtocol.decodeStatus(truncated));
	}

	/**
	 * 编码 CONFIG 再解码。
	 *
	 * @param deviceName 设备名
	 * @return 解码结果
	 * @throws Exception 协议错误
	 */
	private static AppleBridgeProtocol.Config roundTripConfig(final String deviceName) throws Exception {
		final byte[] bytes = toBytes(AppleBridgeProtocol.encodeConfig(new AppleBridgeProtocol.Config(1, 48000, 8,
			AppleBridgeProtocol.LAYOUT_SURROUND_7_1, 0, 1440, deviceName)));
		final int nameLength = Short.toUnsignedInt(payloadOf(bytes).getShort(20));
		assertTrue(nameLength <= 1024, "设备名不应超过 1024 字节，实际 " + nameLength);
		assertEquals(AppleBridgeProtocol.HEADER_SIZE + 22 + nameLength, bytes.length);
		return AppleBridgeProtocol.decodeConfig(payloadOf(bytes));
	}

	/**
	 * 构造一个消息头。
	 *
	 * @param magic 魔数
	 * @param type 消息类型
	 * @param length 负载长度
	 * @return 已翻转的小端序缓冲区
	 */
	private static ByteBuffer header(final int magic, final int type, final int length) {
		final ByteBuffer b = ByteBuffer.allocate(AppleBridgeProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(magic).putShort((short) type).putShort((short) 0).putInt(length);
		return b.flip();
	}

	/**
	 * 取出缓冲区剩余的全部字节（不修改原缓冲区）。
	 *
	 * @param message 消息
	 * @return 字节数组
	 */
	private static byte[] toBytes(final ByteBuffer message) {
		final ByteBuffer copy = message.duplicate();
		final byte[] bytes = new byte[copy.remaining()];
		copy.get(bytes);
		return bytes;
	}

	/**
	 * 跳过消息头，返回负载部分（小端序，位置从 0 开始）。
	 *
	 * @param message 完整消息
	 * @return 负载
	 */
	private static ByteBuffer payloadOf(final byte[] message) {
		return ByteBuffer.wrap(message, AppleBridgeProtocol.HEADER_SIZE, message.length - AppleBridgeProtocol.HEADER_SIZE)
			.slice()
			.order(ByteOrder.LITTLE_ENDIAN);
	}
}
