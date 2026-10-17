package io.github.obsidianplusplus.spatialaudio.math;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 四元数工具测试：验证头部坐标系约定（x 右、y 前、z 上）与欧拉角分解。
 */
class QuatTest {
	/** 浮点比较容差。 */
	private static final double EPS = 1.0e-9;

	/**
	 * 偏航为正（向左转头）时，前向应转向 -x（左侧）。
	 */
	@Test
	void positiveYawTurnsLeft() {
		final Quat q = Quat.fromEulerZXY(Math.toRadians(90.0), 0.0, 0.0);
		assertArrayEquals(new double[] {-1.0, 0.0, 0.0}, q.rotate(0.0, 1.0, 0.0), EPS);
		assertEquals(Math.toRadians(90.0), q.yaw(), EPS);
	}

	/**
	 * 俯仰为正（抬头）时，前向应抬向 +z。
	 */
	@Test
	void positivePitchLooksUp() {
		final Quat q = Quat.fromEulerZXY(0.0, Math.toRadians(30.0), 0.0);
		final double[] forward = q.rotate(0.0, 1.0, 0.0);
		assertEquals(Math.sin(Math.toRadians(30.0)), forward[2], EPS);
	}

	/**
	 * 横滚为正（向右歪头）时，头顶应倒向 +x。
	 */
	@Test
	void positiveRollTiltsRight() {
		final Quat q = Quat.fromEulerZXY(0.0, 0.0, Math.toRadians(20.0));
		final double[] up = q.rotate(0.0, 0.0, 1.0);
		assertEquals(Math.sin(Math.toRadians(20.0)), up[0], EPS);
	}

	/**
	 * 欧拉角分解与合成互为逆运算。
	 */
	@Test
	void eulerRoundTrip() {
		final double yaw = Math.toRadians(-47.0);
		final double pitch = Math.toRadians(23.0);
		final double roll = Math.toRadians(-11.0);
		final double[] euler = Quat.fromEulerZXY(yaw, pitch, roll).toEulerZXY();
		assertArrayEquals(new double[] {yaw, pitch, roll}, euler, 1.0e-9);
	}

	/**
	 * 共轭即逆旋转。
	 */
	@Test
	void conjugateIsInverse() {
		final Quat q = Quat.fromEulerZXY(0.4, -0.2, 0.7);
		final Quat identity = q.multiply(q.conjugate());
		assertEquals(1.0, Math.abs(identity.w()), EPS);
		assertEquals(0.0, identity.angle(), 1.0e-7);
	}

	/**
	 * 球面插值在端点与中点的行为正确，并走最短路径。
	 */
	@Test
	void slerpEndpointsAndMidpoint() {
		final Quat a = Quat.IDENTITY;
		final Quat b = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(80.0));
		assertEquals(0.0, a.slerp(b, 0.0).angle(), 1.0e-7);
		assertEquals(Math.toRadians(80.0), a.slerp(b, 1.0).angle(), 1.0e-7);
		assertEquals(Math.toRadians(40.0), a.slerp(b, 0.5).angle(), 1.0e-7);
		final Quat negated = new Quat(-b.x(), -b.y(), -b.z(), -b.w());
		assertEquals(Math.toRadians(40.0), a.slerp(negated, 0.5).angle(), 1.0e-7);
	}

	/**
	 * 指数与对数映射互为逆运算，旋转角缩放正确。
	 */
	@Test
	void expLogAndScale() {
		final Quat q = Quat.fromAxisAngle(1.0, 2.0, -0.5, 1.1);
		final double[] v = q.log();
		final Quat back = Quat.exp(v[0], v[1], v[2]);
		assertTrue(Math.abs(q.dot(back)) > 1.0 - 1.0e-12);
		assertEquals(0.55, q.scaleAngle(0.5).angle(), 1.0e-9);
	}

	/**
	 * 非法数值归一化后回退为单位四元数。
	 */
	@Test
	void normalizeHandlesInvalid() {
		assertEquals(Quat.IDENTITY, new Quat(Double.NaN, 0.0, 0.0, 0.0).normalize());
		assertEquals(Quat.IDENTITY, new Quat(0.0, 0.0, 0.0, 0.0).normalize());
	}
}
