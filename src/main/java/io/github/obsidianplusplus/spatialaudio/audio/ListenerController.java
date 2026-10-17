package io.github.obsidianplusplus.spatialaudio.audio;

import io.github.obsidianplusplus.spatialaudio.config.ConfigManager;
import io.github.obsidianplusplus.spatialaudio.config.SpatialAudioConfig;
import io.github.obsidianplusplus.spatialaudio.headtracking.HeadTrackingManager;
import io.github.obsidianplusplus.spatialaudio.math.Quat;
import org.lwjgl.openal.AL10;

/**
 * 听者（玩家耳朵）朝向控制：把“摄像机朝向”与“真实头部旋转”合成为最终的 OpenAL 听者朝向。
 *
 * <p>原理：摄像机给出游戏内的视线方向（鼠标控制），头部追踪给出玩家真实头部相对屏幕的旋转。
 * 设摄像机的右、前、上三个基向量为 R、F、U，头部旋转在头部坐标系（x 右、y 前、z 上）中为 q，
 * 则最终的听者前向为 {@code q·(0,1,0)} 在 (R,F,U) 基下的展开，向上方向同理。
 * 这样声音被“固定在世界中”：向左转头时，正前方的声源会移动到右耳一侧，与现实一致。
 *
 * <p>本类只在 Minecraft 声音线程中调用，因此内部状态无需加锁。
 */
public final class ListenerController {
	/** 摄像机前向（世界坐标）。 */
	private final double[] cameraForward = {0.0, 0.0, -1.0};

	/** 摄像机上方向（世界坐标）。 */
	private final double[] cameraUp = {0.0, 1.0, 0.0};

	/** 摄像机位置（世界坐标）。 */
	private final double[] cameraPosition = {0.0, 0.0, 0.0};

	/** 最近一次使用的头部旋转（volatile：界面线程会读取用于显示）。 */
	private volatile Quat head = Quat.IDENTITY;

	/** 最近一次头部偏航（弧度，左转为正）。 */
	private double headYaw;

	/** 听者速度（格/秒），用于多普勒效应。 */
	private final double[] velocity = {0.0, 0.0, 0.0};

	/** 上一次记录位置的时刻（纳秒）。 */
	private long lastPositionNanos;

	/** 是否已经收到过摄像机数据。 */
	private boolean hasCamera;

	/**
	 * 原版写入听者朝向时调用：记录摄像机朝向，返回合成头部旋转后的朝向。
	 *
	 * @param vanilla 原版朝向数组 {前x, 前y, 前z, 上x, 上y, 上z}
	 * @return 合成后的朝向数组
	 */
	public float[] onCameraOrientation(final float[] vanilla) {
		if (vanilla == null || vanilla.length < 6) {
			return vanilla;
		}
		this.cameraForward[0] = vanilla[0];
		this.cameraForward[1] = vanilla[1];
		this.cameraForward[2] = vanilla[2];
		this.cameraUp[0] = vanilla[3];
		this.cameraUp[1] = vanilla[4];
		this.cameraUp[2] = vanilla[5];
		this.hasCamera = true;
		this.head = this.sampleHead();
		return this.compose(this.head);
	}

	/**
	 * 原版写入听者位置时调用：记录位置并估计速度，返回叠加头部平移后的位置。
	 *
	 * @param x 世界坐标 x
	 * @param y 世界坐标 y
	 * @param z 世界坐标 z
	 * @return 实际写入 OpenAL 的位置
	 */
	public float[] onCameraPosition(final float x, final float y, final float z) {
		final long now = System.nanoTime();
		if (this.lastPositionNanos != 0L) {
			final double dt = (now - this.lastPositionNanos) / 1.0e9;
			if (dt > 1.0e-4) {
				final double vx = (x - this.cameraPosition[0]) / dt;
				final double vy = (y - this.cameraPosition[1]) / dt;
				final double vz = (z - this.cameraPosition[2]) / dt;
				final double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
				// 传送或跨维度时速度会异常巨大，直接清零，避免多普勒产生刺耳的音高突变。
				final double alpha = 1.0 - Math.exp(-dt / 0.1);
				if (speed > 80.0) {
					this.velocity[0] = 0.0;
					this.velocity[1] = 0.0;
					this.velocity[2] = 0.0;
				} else {
					this.velocity[0] += alpha * (vx - this.velocity[0]);
					this.velocity[1] += alpha * (vy - this.velocity[1]);
					this.velocity[2] += alpha * (vz - this.velocity[2]);
				}
			}
		}
		this.lastPositionNanos = now;
		this.cameraPosition[0] = x;
		this.cameraPosition[1] = y;
		this.cameraPosition[2] = z;
		return this.positionWithTranslation();
	}

	/**
	 * 周期性刷新（声音线程）：使用最新头部姿态重新写入听者朝向与位置。
	 *
	 * @param writePosition 是否同时写入位置
	 */
	public void refresh(final boolean writePosition) {
		if (!this.hasCamera) {
			return;
		}
		final Quat next = this.sampleHead();
		final boolean headChanged = Math.abs(next.dot(this.head)) < 1.0 - 1.0e-12;
		this.head = next;
		if (!headChanged && !writePosition) {
			// 头部姿态没有变化时无需重复写入，摄像机变化由原版每帧更新负责。
			return;
		}
		AL10.alListenerfv(AL10.AL_ORIENTATION, this.compose(this.head));
		if (writePosition) {
			final float[] position = this.positionWithTranslation();
			AL10.alListener3f(AL10.AL_POSITION, position[0], position[1], position[2]);
		}
	}

	/**
	 * 写入听者速度（多普勒效应），关闭时写入零。
	 *
	 * @param enabled 是否启用多普勒
	 */
	public void writeVelocity(final boolean enabled) {
		if (enabled) {
			AL10.alListener3f(AL10.AL_VELOCITY, (float) this.velocity[0], (float) this.velocity[1], (float) this.velocity[2]);
		} else {
			AL10.alListener3f(AL10.AL_VELOCITY, 0.0f, 0.0f, 0.0f);
		}
	}

	/**
	 * 最近一次使用的头部旋转。
	 *
	 * @return 头部坐标系下的旋转
	 */
	public Quat head() {
		return this.head;
	}

	/**
	 * 最近一次的头部偏航角。
	 *
	 * @return 弧度，左转为正
	 */
	public double headYaw() {
		return this.headYaw;
	}

	/**
	 * 摄像机位置（世界坐标）。
	 *
	 * @return 新数组 {x, y, z}
	 */
	public double[] position() {
		return this.cameraPosition.clone();
	}

	/**
	 * 把相对听者的偏移锚定到屏幕方向：对偏移施加头部旋转的逆变换。
	 *
	 * <p>OpenAL 相对声源的坐标系为 x 右、y 上、z 后；先换算到头部坐标系（x 右、y 前、z 上），
	 * 施加逆旋转后再换算回来。
	 *
	 * @param x 偏移 x
	 * @param y 偏移 y
	 * @param z 偏移 z
	 * @return 锚定后的偏移 {x, y, z}
	 */
	public float[] anchorRelative(final double x, final double y, final double z) {
		return anchorRelative(this.head, x, y, z);
	}

	/**
	 * 锚定计算的纯函数版本（便于单元测试）。
	 *
	 * @param rotation 头部旋转
	 * @param x 偏移 x
	 * @param y 偏移 y
	 * @param z 偏移 z
	 * @return 锚定后的偏移 {x, y, z}
	 */
	static float[] anchorRelative(final Quat rotation, final double x, final double y, final double z) {
		final double[] h = rotation.conjugate().rotate(x, -z, y);
		return new float[] {(float) h[0], (float) h[2], (float) -h[1]};
	}

	/**
	 * 计算屏幕锚定的立体声虚拟扬声器角度（逆时针为正，弧度）。
	 *
	 * @return {左声道角度, 右声道角度}，均已规范到 [-π, π]
	 */
	public float[] anchoredStereoAngles() {
		return stereoAngles(this.headYaw);
	}

	/**
	 * 立体声锚定角度的纯函数版本：屏幕上 ±30° 的虚拟扬声器在头部坐标系中的方位。
	 *
	 * @param yaw 头部偏航（弧度，左转为正）
	 * @return {左声道角度, 右声道角度}
	 */
	static float[] stereoAngles(final double yaw) {
		final double base = Math.PI / 6.0;
		return new float[] {(float) wrapAngle(base - yaw), (float) wrapAngle(-base - yaw)};
	}

	/**
	 * 读取最新头部旋转；模组关闭或由 Apple 负责头部追踪时返回单位旋转。
	 *
	 * <p>Apple 空间音频方式下，OpenAL 渲染的声场必须以摄像机为参照，头部旋转由助手中的 Apple 引擎施加；
	 * Apple 系统空间化立体声方式下，头部追踪由 macOS 完成。两种情况下都不能再旋转 OpenAL 听者，否则会被计算两次。
	 *
	 * @return 头部旋转
	 */
	private Quat sampleHead() {
		final SpatialAudioConfig config = ConfigManager.get();
		final boolean rotate = config.enabled && AudioSystem.get().activeRenderer().rotatesOpenAlListener();
		final Quat rotation = rotate ? HeadTrackingManager.get().currentRotation() : Quat.IDENTITY;
		this.headYaw = rotation.yaw();
		return rotation;
	}

	/**
	 * 用摄像机基向量展开头部旋转后的前向与上方向。
	 *
	 * @param rotation 头部旋转
	 * @return 朝向数组 {前x, 前y, 前z, 上x, 上y, 上z}
	 */
	private float[] compose(final Quat rotation) {
		return composeOrientation(this.cameraForward, this.cameraUp, rotation);
	}

	/**
	 * 合成计算的纯函数版本（便于单元测试）。
	 *
	 * @param f 摄像机前向
	 * @param u 摄像机上方向
	 * @param rotation 头部旋转
	 * @return 朝向数组 {前x, 前y, 前z, 上x, 上y, 上z}
	 */
	static float[] composeOrientation(final double[] f, final double[] u, final Quat rotation) {
		// 右向量 R = F × U，与原版 ListenerTransform.right() 一致。
		final double[] r = {
			f[1] * u[2] - f[2] * u[1],
			f[2] * u[0] - f[0] * u[2],
			f[0] * u[1] - f[1] * u[0]
		};
		final double[] hf = rotation.rotate(0.0, 1.0, 0.0);
		final double[] hu = rotation.rotate(0.0, 0.0, 1.0);
		final double[] forward = combine(r, f, u, hf);
		final double[] up = combine(r, f, u, hu);
		normalize(forward);
		normalize(up);
		return new float[] {
			(float) forward[0], (float) forward[1], (float) forward[2],
			(float) up[0], (float) up[1], (float) up[2]
		};
	}

	/**
	 * 叠加头部平移后的听者位置。
	 *
	 * @return 位置数组
	 */
	private float[] positionWithTranslation() {
		final double[] translation = HeadTrackingManager.get().currentTranslation();
		if (translation == null || !ConfigManager.get().enabled || !AudioSystem.get().activeRenderer().rotatesOpenAlListener()) {
			return new float[] {(float) this.cameraPosition[0], (float) this.cameraPosition[1], (float) this.cameraPosition[2]};
		}
		final double[] f = this.cameraForward;
		final double[] u = this.cameraUp;
		final double[] r = {
			f[1] * u[2] - f[2] * u[1],
			f[2] * u[0] - f[0] * u[2],
			f[0] * u[1] - f[1] * u[0]
		};
		// 平移量位于听者坐标系（x 右、y 上、z 后），后方向即 -F。
		final double ox = translation[0] * r[0] + translation[1] * u[0] - translation[2] * f[0];
		final double oy = translation[0] * r[1] + translation[1] * u[1] - translation[2] * f[1];
		final double oz = translation[0] * r[2] + translation[1] * u[2] - translation[2] * f[2];
		return new float[] {
			(float) (this.cameraPosition[0] + ox),
			(float) (this.cameraPosition[1] + oy),
			(float) (this.cameraPosition[2] + oz)
		};
	}

	/**
	 * 以 (R, F, U) 为基展开头部坐标系向量。
	 *
	 * @param r 右基向量
	 * @param f 前基向量
	 * @param u 上基向量
	 * @param h 头部坐标系向量 {x 右, y 前, z 上}
	 * @return 世界坐标向量
	 */
	private static double[] combine(final double[] r, final double[] f, final double[] u, final double[] h) {
		return new double[] {
			h[0] * r[0] + h[1] * f[0] + h[2] * u[0],
			h[0] * r[1] + h[1] * f[1] + h[2] * u[1],
			h[0] * r[2] + h[1] * f[2] + h[2] * u[2]
		};
	}

	/**
	 * 原地归一化。
	 *
	 * @param v 向量
	 */
	private static void normalize(final double[] v) {
		final double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (length > 1.0e-9) {
			v[0] /= length;
			v[1] /= length;
			v[2] /= length;
		}
	}

	/**
	 * 把角度规范到 [-π, π]。
	 *
	 * @param angle 弧度
	 * @return 规范后的弧度
	 */
	static double wrapAngle(final double angle) {
		double a = angle % (Math.PI * 2.0);
		if (a > Math.PI) {
			a -= Math.PI * 2.0;
		} else if (a < -Math.PI) {
			a += Math.PI * 2.0;
		}
		return a;
	}
}
