package io.github.obsidianplusplus.spatialaudio.headtracking;

import io.github.obsidianplusplus.spatialaudio.math.Quat;

/**
 * 一帧头部姿态采样。
 *
 * @param origin 数据来源
 * @param rotation 头部旋转（头部坐标系：x 右、y 前、z 上；相对于追踪器自身的参考系）
 * @param sensorTime 传感器时间戳（秒），未知时为 NaN
 * @param receivedNanos 本机接收时刻（{@link System#nanoTime()}）
 * @param rotationRate 陀螺仪角速度（头部坐标系，弧度/秒），未知时为 null
 * @param translation 头部平移（米，OpenAL 听者坐标系：x 右、y 上、z 后），未知时为 null
 */
public record PoseSample(
	Origin origin,
	Quat rotation,
	double sensorTime,
	long receivedNanos,
	double[] rotationRate,
	double[] translation
) {
	/**
	 * 姿态数据来源。
	 */
	public enum Origin {
		/** AirPods（经 macOS 助手转发的 CoreMotion 数据）。 */
		AIRPODS,
		/** OpenTrack 兼容追踪器。 */
		OPENTRACK
	}
}
