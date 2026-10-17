package io.github.obsidianplusplus.spatialaudio.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * One Euro 旋转滤波器测试。
 */
class OneEuroFilterTest {
	/**
	 * 静止输入叠加小幅抖动时，输出抖动应明显小于输入。
	 */
	@Test
	void suppressesJitterAtRest() {
		final OneEuroFilter filter = new OneEuroFilter(1.0, 0.3);
		final Random random = new Random(42L);
		final double jitter = Math.toRadians(0.5);
		double inputSpread = 0.0;
		double outputSpread = 0.0;
		for (int i = 0; i < 500; i++) {
			final double noise = (random.nextDouble() * 2.0 - 1.0) * jitter;
			final Quat raw = Quat.fromAxisAngle(0.0, 0.0, 1.0, noise);
			final Quat out = filter.filter(raw, 0.02);
			if (i > 100) {
				inputSpread += Math.abs(noise);
				outputSpread += out.angle();
			}
		}
		assertTrue(outputSpread < inputSpread * 0.5, "滤波后抖动应至少减半");
	}

	/**
	 * 阶跃输入最终收敛到目标值。
	 */
	@Test
	void convergesToStep() {
		final OneEuroFilter filter = new OneEuroFilter(1.0, 0.3);
		filter.filter(Quat.IDENTITY, 0.02);
		final Quat target = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(60.0));
		Quat out = Quat.IDENTITY;
		for (int i = 0; i < 300; i++) {
			out = filter.filter(target, 0.02);
		}
		assertEquals(0.0, out.conjugate().multiply(target).angle(), Math.toRadians(0.5));
	}

	/**
	 * 快速转动时自适应截止频率提高，跟随误差小于慢速固定滤波。
	 */
	@Test
	void fastMotionHasLowLag() {
		final OneEuroFilter adaptive = new OneEuroFilter(1.0, 1.0);
		final OneEuroFilter fixed = new OneEuroFilter(1.0, 0.0);
		double adaptiveError = 0.0;
		double fixedError = 0.0;
		for (int i = 0; i < 100; i++) {
			// 以 180°/秒 匀速转头。
			final Quat raw = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(180.0) * i * 0.01);
			adaptiveError += adaptive.filter(raw, 0.01).conjugate().multiply(raw).angle();
			fixedError += fixed.filter(raw, 0.01).conjugate().multiply(raw).angle();
		}
		assertTrue(adaptiveError < fixedError, "自适应滤波在快速运动时应更跟手");
	}

	/**
	 * 平滑系数公式：截止频率越高、时间间隔越长，系数越接近 1。
	 */
	@Test
	void smoothingFactorMonotonic() {
		assertTrue(OneEuroFilter.smoothingFactor(0.01, 10.0) > OneEuroFilter.smoothingFactor(0.01, 1.0));
		assertTrue(OneEuroFilter.smoothingFactor(0.1, 1.0) > OneEuroFilter.smoothingFactor(0.01, 1.0));
	}
}
