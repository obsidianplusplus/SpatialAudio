package io.github.obsidianplusplus.spatialaudio.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.obsidianplusplus.spatialaudio.math.Quat;
import org.junit.jupiter.api.Test;

/**
 * 听者朝向合成与屏幕锚定测试。
 */
class ListenerControllerTest {
	/** 摄像机朝向北（-z），头顶朝上。 */
	private static final double[] FORWARD = {0.0, 0.0, -1.0};

	/** 摄像机上方向。 */
	private static final double[] UP = {0.0, 1.0, 0.0};

	/**
	 * 无头部旋转时，合成结果等于摄像机朝向。
	 */
	@Test
	void identityKeepsCameraOrientation() {
		final float[] o = ListenerController.composeOrientation(FORWARD, UP, Quat.IDENTITY);
		assertArrayEquals(new float[] {0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f}, o, 1.0e-6f);
	}

	/**
	 * 向左转头 90°：面朝北时听者前向变为西（-x），正前方的声源因此出现在右耳一侧。
	 */
	@Test
	void yawLeftTurnsListenerWest() {
		final float[] o = ListenerController.composeOrientation(FORWARD, UP, Quat.fromEulerZXY(Math.toRadians(90.0), 0.0, 0.0));
		assertArrayEquals(new float[] {-1.0f, 0.0f, 0.0f}, new float[] {o[0], o[1], o[2]}, 1.0e-6f);
		assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f}, new float[] {o[3], o[4], o[5]}, 1.0e-6f);
	}

	/**
	 * 抬头 30°：听者前向向上倾斜。
	 */
	@Test
	void pitchUpRaisesForward() {
		final float[] o = ListenerController.composeOrientation(FORWARD, UP, Quat.fromEulerZXY(0.0, Math.toRadians(30.0), 0.0));
		assertEquals(Math.sin(Math.toRadians(30.0)), o[1], 1.0e-6);
		assertEquals(-Math.cos(Math.toRadians(30.0)), o[2], 1.0e-6);
	}

	/**
	 * 摄像机自身俯视时，头部偏航仍绕“摄像机上方向”旋转（头部旋转叠加在视角之上）。
	 */
	@Test
	void headRotationIsRelativeToCamera() {
		final double s = Math.sqrt(0.5);
		final double[] lookingDown = {0.0, -s, -s};
		final double[] upTilted = {0.0, s, -s};
		final float[] o = ListenerController.composeOrientation(lookingDown, upTilted, Quat.fromEulerZXY(Math.toRadians(90.0), 0.0, 0.0));
		// 右向量 R = F × U = (1, 0, 0)；左转 90° 后前向 = -R。
		assertArrayEquals(new float[] {-1.0f, 0.0f, 0.0f}, new float[] {o[0], o[1], o[2]}, 1.0e-6f);
	}

	/**
	 * 屏幕锚定：头向左转 90° 后，原本在正前方（-z）的相对声源应出现在听者右侧（+x）。
	 */
	@Test
	void anchoredRelativeSourceStaysInFrontOfScreen() {
		final float[] p = ListenerController.anchorRelative(Quat.fromEulerZXY(Math.toRadians(90.0), 0.0, 0.0), 0.0, 0.0, -2.0);
		assertArrayEquals(new float[] {2.0f, 0.0f, 0.0f}, p, 1.0e-6f);
	}

	/**
	 * 立体声锚定角度：头向左转 30° 时，左声道虚拟扬声器正好在正前方。
	 */
	@Test
	void stereoAnglesFollowYaw() {
		final float[] centered = ListenerController.stereoAngles(0.0);
		assertArrayEquals(new float[] {(float) (Math.PI / 6.0), (float) (-Math.PI / 6.0)}, centered, 1.0e-6f);
		final float[] turned = ListenerController.stereoAngles(Math.toRadians(30.0));
		assertEquals(0.0f, turned[0], 1.0e-6f);
		assertEquals((float) Math.toRadians(-60.0), turned[1], 1.0e-6f);
		final float[] wrapped = ListenerController.stereoAngles(Math.toRadians(170.0));
		assertEquals((float) Math.toRadians(-140.0), wrapped[0], 1.0e-5f);
	}
}
