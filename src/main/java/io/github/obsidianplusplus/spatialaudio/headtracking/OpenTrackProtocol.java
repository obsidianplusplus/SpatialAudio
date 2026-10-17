package io.github.obsidianplusplus.spatialaudio.headtracking;

import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * OpenTrack「UDP over network」输出协议的解析。
 *
 * <p>OpenTrack 每帧发送 6 个小端序 double：X、Y、Z 平移（厘米）以及 Yaw、Pitch、Roll 旋转（角度）。
 * 部分发送端会在末尾附加 4 字节帧号，解析时忽略。
 * OpenTrack 的约定是向右转头偏航为正、抬头俯仰为正；本模组内部约定向左转头偏航为正，
 * 因此解析时对偏航取反。若某些追踪器方向相反，可在配置中逐轴反转。
 */
public final class OpenTrackProtocol {
	/** 标准数据包长度（6 × 8 字节）。 */
	public static final int PACKET_SIZE = 48;

	/** 带帧号的数据包长度。 */
	public static final int PACKET_SIZE_WITH_FRAME = 52;

	/** 角度合法范围（超过视为异常数据）。 */
	private static final double MAX_ANGLE = 360.0;

	/** 平移合法范围（厘米）。 */
	private static final double MAX_TRANSLATION_CM = 500.0;

	private OpenTrackProtocol() {
	}

	/**
	 * 解析后的 OpenTrack 姿态。
	 *
	 * @param rotation 头部旋转（内部头部坐标系）
	 * @param translation 平移（米，OpenAL 听者坐标系：x 右、y 上、z 后）
	 */
	public record Pose(Quat rotation, double[] translation) {
	}

	/**
	 * 判断数据长度是否符合 OpenTrack 协议。
	 *
	 * @param length 数据长度
	 * @return 符合时返回 true
	 */
	public static boolean matches(final int length) {
		return length == PACKET_SIZE || length == PACKET_SIZE_WITH_FRAME;
	}

	/**
	 * 解析数据包。
	 *
	 * @param buffer 缓冲区，位置 0 为包头
	 * @param length 有效长度
	 * @return 解析结果；格式不符或数值异常时返回 null
	 */
	public static Pose decode(final ByteBuffer buffer, final int length) {
		if (!matches(length)) {
			return null;
		}
		final ByteBuffer b = buffer.order(ByteOrder.LITTLE_ENDIAN);
		final double tx = b.getDouble(0);
		final double ty = b.getDouble(8);
		final double tz = b.getDouble(16);
		final double yaw = b.getDouble(24);
		final double pitch = b.getDouble(32);
		final double roll = b.getDouble(40);
		if (!isSane(yaw, MAX_ANGLE) || !isSane(pitch, MAX_ANGLE) || !isSane(roll, MAX_ANGLE)
			|| !isSane(tx, MAX_TRANSLATION_CM) || !isSane(ty, MAX_TRANSLATION_CM) || !isSane(tz, MAX_TRANSLATION_CM)) {
			return null;
		}
		final Quat rotation = Quat.fromEulerZXY(Math.toRadians(-yaw), Math.toRadians(pitch), Math.toRadians(roll));
		// OpenTrack 的 Z 轴正方向为远离屏幕（向后），与 OpenAL 听者坐标系一致；单位由厘米换算为米。
		final double[] translation = {tx / 100.0, ty / 100.0, tz / 100.0};
		return new Pose(rotation, translation);
	}

	/**
	 * 编码一帧 OpenTrack 数据（用于单元测试）。
	 *
	 * @param tx X 平移（厘米）
	 * @param ty Y 平移（厘米）
	 * @param tz Z 平移（厘米）
	 * @param yaw 偏航（角度，右转为正）
	 * @param pitch 俯仰（角度，抬头为正）
	 * @param roll 横滚（角度）
	 * @return 48 字节数组
	 */
	public static byte[] encode(final double tx, final double ty, final double tz, final double yaw, final double pitch, final double roll) {
		final ByteBuffer b = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		b.putDouble(tx).putDouble(ty).putDouble(tz).putDouble(yaw).putDouble(pitch).putDouble(roll);
		return b.array();
	}

	/**
	 * 检查数值是否有限且在范围内。
	 *
	 * @param value 数值
	 * @param limit 绝对值上限
	 * @return 合法时返回 true
	 */
	private static boolean isSane(final double value, final double limit) {
		return Double.isFinite(value) && Math.abs(value) <= limit;
	}
}
