package io.github.obsidianplusplus.spatialaudio.headtracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.obsidianplusplus.spatialaudio.config.RecenterMode;
import io.github.obsidianplusplus.spatialaudio.math.Quat;
import org.junit.jupiter.api.Test;

/**
 * 头部姿态处理管线测试：回中、追踪、预测、轴向开关、反转与中断回归。
 */
class HeadPoseProcessorTest {
	/** 一帧间隔（纳秒，50 Hz）。 */
	private static final long FRAME = 20_000_000L;

	/** 不预测、不平滑、不漂移校正的参数，便于精确断言。 */
	private static final HeadPoseProcessor.Settings RAW = new HeadPoseProcessor.Settings(
		1.0, true, true, true, false, false, false, RecenterMode.FULL, true, false, 25.0, 0.0, 20.0, 5.0);

	/**
	 * 生成 AirPods 样本。
	 *
	 * @param rotation 姿态
	 * @param index 帧序号
	 * @return 样本
	 */
	private static PoseSample sample(final Quat rotation, final int index) {
		return new PoseSample(PoseSample.Origin.AIRPODS, rotation, index * 0.02, 1_000_000_000L + index * FRAME, null, null);
	}

	/**
	 * 第一帧自动回中：无论追踪器参考系如何，初始输出都是“正对屏幕”。
	 */
	@Test
	void firstSampleRecenters() {
		final HeadPoseProcessor processor = new HeadPoseProcessor();
		processor.setSettings(RAW);
		final Quat arbitrary = Quat.fromEulerZXY(Math.toRadians(130.0), Math.toRadians(-12.0), Math.toRadians(4.0));
		processor.onSample(sample(arbitrary, 0));
		assertEquals(0.0, processor.currentRelative(1_000_000_000L).angle(), 1.0e-6);
	}

	/**
	 * 回中后向左转头 30°，输出偏航为 +30°。
	 */
	@Test
	void tracksYawRelativeToReference() {
		final HeadPoseProcessor processor = new HeadPoseProcessor();
		processor.setSettings(RAW);
		final Quat reference = Quat.fromEulerZXY(Math.toRadians(70.0), 0.0, 0.0);
		processor.onSample(sample(reference, 0));
		final Quat turned = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(30.0)).multiply(reference);
		for (int i = 1; i <= 50; i++) {
			processor.onSample(sample(turned, i));
		}
		final double yaw = Math.toDegrees(processor.currentRelative(1_000_000_000L + 50 * FRAME).toEulerZXY()[0]);
		assertEquals(30.0, yaw, 0.5);
	}

	/**
	 * 关闭偏航追踪后，转头不改变输出。
	 */
	@Test
	void disabledAxisIsIgnored() {
		final HeadPoseProcessor processor = new HeadPoseProcessor();
		processor.setSettings(new HeadPoseProcessor.Settings(1.0, false, true, true, false, false, false,
			RecenterMode.FULL, true, false, 25.0, 0.0, 20.0, 5.0));
		processor.onSample(sample(Quat.IDENTITY, 0));
		final Quat turned = Quat.fromEulerZXY(Math.toRadians(40.0), Math.toRadians(10.0), 0.0);
		for (int i = 1; i <= 50; i++) {
			processor.onSample(sample(turned, i));
		}
		final double[] euler = processor.currentRelative(1_000_000_000L + 50 * FRAME).toEulerZXY();
		assertEquals(0.0, Math.toDegrees(euler[0]), 0.5);
		assertEquals(10.0, Math.toDegrees(euler[1]), 0.5);
	}

	/**
	 * 偏航反转用于校准方向相反的追踪器。
	 */
	@Test
	void invertYawFlipsDirection() {
		final HeadPoseProcessor processor = new HeadPoseProcessor();
		processor.setSettings(new HeadPoseProcessor.Settings(1.0, true, true, true, true, false, false,
			RecenterMode.FULL, true, false, 25.0, 0.0, 20.0, 5.0));
		processor.onSample(sample(Quat.IDENTITY, 0));
		for (int i = 1; i <= 50; i++) {
			processor.onSample(sample(Quat.fromEulerZXY(Math.toRadians(25.0), 0.0, 0.0), i));
		}
		final double yaw = Math.toDegrees(processor.currentRelative(1_000_000_000L + 50 * FRAME).toEulerZXY()[0]);
		assertEquals(-25.0, yaw, 0.5);
	}

	/**
	 * 预测：匀速转头时，带预测的输出领先于当前样本。
	 */
	@Test
	void predictionLeadsMotion() {
		final HeadPoseProcessor processor = new HeadPoseProcessor();
		processor.setSettings(new HeadPoseProcessor.Settings(1.0, true, true, true, false, false, false,
			RecenterMode.FULL, true, false, 25.0, 0.05, 20.0, 5.0));
		final double speed = Math.toRadians(90.0);
		int index = 0;
		for (; index <= 50; index++) {
			final Quat pose = Quat.fromAxisAngle(0.0, 0.0, 1.0, speed * index * 0.02);
			processor.onSample(new PoseSample(PoseSample.Origin.AIRPODS, pose, index * 0.02,
				1_000_000_000L + index * FRAME, new double[] {0.0, 0.0, speed}, null));
		}
		final double current = Math.toDegrees(speed * 50 * 0.02);
		final double predicted = Math.toDegrees(processor.currentRelative(1_000_000_000L + 50 * FRAME).toEulerZXY()[0]);
		assertTrue(predicted > current + 3.0, "预测值应领先约 4.5°，实际 " + (predicted - current));
	}

	/**
	 * 数据中断后输出平滑回归正前方。
	 */
	@Test
	void staleDataFadesToIdentity() {
		final HeadPoseProcessor processor = new HeadPoseProcessor();
		processor.setSettings(RAW);
		processor.onSample(sample(Quat.IDENTITY, 0));
		final Quat turned = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(45.0));
		for (int i = 1; i <= 20; i++) {
			processor.onSample(sample(turned, i));
		}
		long now = 1_000_000_000L + 20 * FRAME;
		assertTrue(processor.currentRelative(now).angle() > Math.toRadians(40.0));
		for (int i = 0; i < 200; i++) {
			now += 10_000_000L;
			processor.currentRelative(now);
		}
		assertFalse(processor.isFresh(now));
		assertEquals(0.0, processor.currentRelative(now).angle(), Math.toRadians(1.0));
	}

	/**
	 * 手动回中把当前姿态重新设为正前方。
	 */
	@Test
	void manualRecenter() {
		final HeadPoseProcessor processor = new HeadPoseProcessor();
		processor.setSettings(RAW);
		processor.onSample(sample(Quat.IDENTITY, 0));
		final Quat turned = Quat.fromEulerZXY(Math.toRadians(-35.0), Math.toRadians(8.0), 0.0);
		for (int i = 1; i <= 30; i++) {
			processor.onSample(sample(turned, i));
		}
		processor.recenter();
		assertEquals(0.0, processor.currentRelative(1_000_000_000L + 30 * FRAME).angle(), Math.toRadians(0.5));
	}

	/**
	 * 漂移校正：头部长时间静止于小偏航角时，偏航逐渐回到 0。
	 */
	@Test
	void driftCorrectionPullsYawToCenter() {
		final HeadPoseProcessor processor = new HeadPoseProcessor();
		processor.setSettings(new HeadPoseProcessor.Settings(1.0, true, true, true, false, false, false,
			RecenterMode.FULL, true, true, 5.0, 0.0, 20.0, 5.0));
		processor.onSample(sample(Quat.IDENTITY, 0));
		final Quat drifted = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(10.0));
		final int frames = 50 * 30;
		for (int i = 1; i <= frames; i++) {
			processor.onSample(sample(drifted, i));
		}
		final double yaw = Math.toDegrees(processor.currentRelative(1_000_000_000L + frames * FRAME).toEulerZXY()[0]);
		assertTrue(Math.abs(yaw) < 2.0, "30 秒后漂移应基本消除，实际 " + yaw);
	}
}
