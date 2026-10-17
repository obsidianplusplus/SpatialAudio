package io.github.obsidianplusplus.spatialaudio.headtracking;

import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * SAHT（SpatialAudio Head Tracking）协议：macOS 助手与模组之间的本机 UDP 数据格式。
 *
 * <p>所有字段均为小端序，单个数据包固定 88 字节，布局如下：
 * <pre>
 * 偏移  类型    含义
 *  0    u32     魔数 "SAHT"（字节 53 41 48 54）
 *  4    u8      协议版本（当前为 1）
 *  5    u8      包类型：1 = 姿态，2 = 状态
 *  6    u16     标志位：bit0 = 姿态有效，bit1 = 含角速度
 *  8    i32     助手状态码（见 STATUS_* 常量）
 * 12    u32     序号
 * 16    f64     传感器时间戳（秒，系统启动以来）
 * 24    f64×4   姿态四元数 x、y、z、w（AirPods 坐标系：x 右、y 前、z 上）
 * 56    f64×3   陀螺仪角速度 x、y、z（弧度/秒）
 * 80    i32     助手进程 PID
 * 84    i32     CoreMotion 授权状态原始值
 * </pre>
 * Swift 助手中的 {@code PacketWriter} 必须与此保持一致。
 */
public final class SahtProtocol {
	/** 数据包长度（字节）。 */
	public static final int PACKET_SIZE = 88;

	/** 魔数（按小端序读取 "SAHT" 四个字节得到的整数）。 */
	public static final int MAGIC = 0x54484153;

	/** 协议版本。 */
	public static final int VERSION = 1;

	/** 包类型：姿态。 */
	public static final int TYPE_POSE = 1;

	/** 包类型：状态。 */
	public static final int TYPE_STATUS = 2;

	/** 标志位：姿态有效。 */
	public static final int FLAG_POSE_VALID = 1;

	/** 标志位：包含角速度。 */
	public static final int FLAG_HAS_RATE = 1 << 1;

	/** 助手状态：启动中。 */
	public static final int STATUS_STARTING = 0;

	/** 助手状态：等待耳机连接或佩戴。 */
	public static final int STATUS_WAITING_FOR_HEADPHONES = 1;

	/** 助手状态：正在输出数据。 */
	public static final int STATUS_ACTIVE = 2;

	/** 助手状态：系统不支持耳机运动数据。 */
	public static final int STATUS_NOT_AVAILABLE = 3;

	/** 助手状态：用户拒绝授权。 */
	public static final int STATUS_NOT_AUTHORIZED = 4;

	/** 助手状态：运动数据出错。 */
	public static final int STATUS_ERROR = 5;

	/** 助手状态：等待用户授权。 */
	public static final int STATUS_AWAITING_AUTHORIZATION = 6;

	private SahtProtocol() {
	}

	/**
	 * 解析后的 SAHT 数据包。
	 *
	 * @param type 包类型
	 * @param flags 标志位
	 * @param status 助手状态码
	 * @param sequence 序号
	 * @param timestamp 传感器时间戳（秒）
	 * @param rotation 姿态四元数
	 * @param rotationRate 角速度（弧度/秒）
	 * @param helperPid 助手进程 PID
	 * @param authorization 授权状态原始值
	 */
	public record Packet(
		int type,
		int flags,
		int status,
		long sequence,
		double timestamp,
		Quat rotation,
		double[] rotationRate,
		int helperPid,
		int authorization
	) {
		/**
		 * 是否为携带有效姿态的数据包。
		 *
		 * @return 有效时返回 true
		 */
		public boolean hasPose() {
			return this.type == TYPE_POSE && (this.flags & FLAG_POSE_VALID) != 0 && this.rotation.isFinite();
		}

		/**
		 * 是否携带角速度。
		 *
		 * @return 携带时返回 true
		 */
		public boolean hasRate() {
			return (this.flags & FLAG_HAS_RATE) != 0;
		}
	}

	/**
	 * 判断缓冲区是否像一个 SAHT 数据包（只检查长度与魔数）。
	 *
	 * @param buffer 缓冲区（任意字节序）
	 * @param length 有效长度
	 * @return 匹配时返回 true
	 */
	public static boolean matches(final ByteBuffer buffer, final int length) {
		return length >= PACKET_SIZE && buffer.order(ByteOrder.LITTLE_ENDIAN).getInt(0) == MAGIC;
	}

	/**
	 * 解析数据包。
	 *
	 * @param buffer 缓冲区，位置 0 为包头
	 * @param length 有效长度
	 * @return 解析结果；格式不符时返回 null
	 */
	public static Packet decode(final ByteBuffer buffer, final int length) {
		if (!matches(buffer, length)) {
			return null;
		}
		final ByteBuffer b = buffer.order(ByteOrder.LITTLE_ENDIAN);
		if ((b.get(4) & 0xFF) != VERSION) {
			return null;
		}
		final int type = b.get(5) & 0xFF;
		final int flags = b.getShort(6) & 0xFFFF;
		final int status = b.getInt(8);
		final long sequence = b.getInt(12) & 0xFFFFFFFFL;
		final double timestamp = b.getDouble(16);
		final Quat rotation = new Quat(b.getDouble(24), b.getDouble(32), b.getDouble(40), b.getDouble(48));
		final double[] rate = {b.getDouble(56), b.getDouble(64), b.getDouble(72)};
		final int pid = b.getInt(80);
		final int authorization = b.getInt(84);
		return new Packet(type, flags, status, sequence, timestamp, rotation.isFinite() ? rotation.normalize() : rotation, rate, pid, authorization);
	}

	/**
	 * 编码数据包（主要用于单元测试与调试工具，正式数据由 Swift 助手发送）。
	 *
	 * @param packet 数据包
	 * @return 88 字节小端序数组
	 */
	public static byte[] encode(final Packet packet) {
		final ByteBuffer b = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0, MAGIC);
		b.put(4, (byte) VERSION);
		b.put(5, (byte) packet.type());
		b.putShort(6, (short) packet.flags());
		b.putInt(8, packet.status());
		b.putInt(12, (int) packet.sequence());
		b.putDouble(16, packet.timestamp());
		b.putDouble(24, packet.rotation().x());
		b.putDouble(32, packet.rotation().y());
		b.putDouble(40, packet.rotation().z());
		b.putDouble(48, packet.rotation().w());
		final double[] rate = packet.rotationRate() == null ? new double[3] : packet.rotationRate();
		b.putDouble(56, rate[0]);
		b.putDouble(64, rate[1]);
		b.putDouble(72, rate[2]);
		b.putInt(80, packet.helperPid());
		b.putInt(84, packet.authorization());
		return b.array();
	}
}
