package io.github.obsidianplusplus.spatialaudio.math;

/**
 * 不可变的双精度单位四元数，用于表示头部旋转。
 *
 * <p>本模组统一使用“头部坐标系”：x 轴指向右耳、y 轴指向正前方（鼻尖）、z 轴指向头顶，
 * 与 Apple CoreMotion 为 AirPods 定义的坐标轴一致，属于右手坐标系。
 * 在该坐标系下：
 * <ul>
 *   <li>偏航（yaw）为绕 z 轴旋转，正值表示向左转头；</li>
 *   <li>俯仰（pitch）为绕 x 轴旋转，正值表示抬头；</li>
 *   <li>横滚（roll）为绕 y 轴旋转，正值表示向右歪头（右耳向下）。</li>
 * </ul>
 * 欧拉角按 Z-X-Y 顺序（先偏航、再俯仰、最后横滚）组合，即 {@code q = Rz(yaw) · Rx(pitch) · Ry(roll)}。
 *
 * @param x 虚部 x
 * @param y 虚部 y
 * @param z 虚部 z
 * @param w 实部
 */
public record Quat(double x, double y, double z, double w) {
	/** 单位四元数（无旋转）。 */
	public static final Quat IDENTITY = new Quat(0.0, 0.0, 0.0, 1.0);

	/** 判断为“零角度”的阈值。 */
	private static final double EPSILON = 1.0e-9;

	/**
	 * 由旋转轴与角度构造四元数。
	 *
	 * @param ax 轴 x（无需归一化）
	 * @param ay 轴 y
	 * @param az 轴 z
	 * @param angle 旋转角（弧度，右手定则）
	 * @return 单位四元数；轴长度为零时返回单位四元数
	 */
	public static Quat fromAxisAngle(final double ax, final double ay, final double az, final double angle) {
		final double length = Math.sqrt(ax * ax + ay * ay + az * az);
		if (length < EPSILON) {
			return IDENTITY;
		}
		final double half = angle * 0.5;
		final double s = Math.sin(half) / length;
		return new Quat(ax * s, ay * s, az * s, Math.cos(half));
	}

	/**
	 * 由 Z-X-Y 顺序的欧拉角构造头部旋转。
	 *
	 * @param yaw 偏航（弧度，左转为正）
	 * @param pitch 俯仰（弧度，抬头为正）
	 * @param roll 横滚（弧度，右倾为正）
	 * @return 单位四元数
	 */
	public static Quat fromEulerZXY(final double yaw, final double pitch, final double roll) {
		final Quat qz = fromAxisAngle(0.0, 0.0, 1.0, yaw);
		final Quat qx = fromAxisAngle(1.0, 0.0, 0.0, pitch);
		final Quat qy = fromAxisAngle(0.0, 1.0, 0.0, roll);
		return qz.multiply(qx).multiply(qy).normalize();
	}

	/**
	 * 由旋转向量（轴 × 角度）构造四元数，即四元数指数映射。
	 *
	 * @param rx 旋转向量 x
	 * @param ry 旋转向量 y
	 * @param rz 旋转向量 z
	 * @return 单位四元数
	 */
	public static Quat exp(final double rx, final double ry, final double rz) {
		final double angle = Math.sqrt(rx * rx + ry * ry + rz * rz);
		if (angle < EPSILON) {
			return IDENTITY;
		}
		return fromAxisAngle(rx, ry, rz, angle);
	}

	/**
	 * 四元数乘法 {@code this ⊗ other}，表示先施加 other 再施加 this。
	 *
	 * @param o 右操作数
	 * @return 乘积
	 */
	public Quat multiply(final Quat o) {
		return new Quat(
			this.w * o.x + this.x * o.w + this.y * o.z - this.z * o.y,
			this.w * o.y - this.x * o.z + this.y * o.w + this.z * o.x,
			this.w * o.z + this.x * o.y - this.y * o.x + this.z * o.w,
			this.w * o.w - this.x * o.x - this.y * o.y - this.z * o.z
		);
	}

	/**
	 * 共轭（对单位四元数即为逆旋转）。
	 *
	 * @return 共轭四元数
	 */
	public Quat conjugate() {
		return new Quat(-this.x, -this.y, -this.z, this.w);
	}

	/**
	 * 归一化；长度异常时返回单位四元数。
	 *
	 * @return 单位四元数
	 */
	public Quat normalize() {
		final double length = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z + this.w * this.w);
		if (!(length > EPSILON) || !Double.isFinite(length)) {
			return IDENTITY;
		}
		return new Quat(this.x / length, this.y / length, this.z / length, this.w / length);
	}

	/**
	 * 四维点积。
	 *
	 * @param o 另一个四元数
	 * @return 点积
	 */
	public double dot(final Quat o) {
		return this.x * o.x + this.y * o.y + this.z * o.z + this.w * o.w;
	}

	/**
	 * 判断四个分量是否都是有限数值。
	 *
	 * @return 合法时返回 true
	 */
	public boolean isFinite() {
		return Double.isFinite(this.x) && Double.isFinite(this.y) && Double.isFinite(this.z) && Double.isFinite(this.w);
	}

	/**
	 * 用本四元数旋转一个三维向量。
	 *
	 * @param vx 向量 x
	 * @param vy 向量 y
	 * @param vz 向量 z
	 * @return 旋转后的新数组 {x, y, z}
	 */
	public double[] rotate(final double vx, final double vy, final double vz) {
		// 使用 v' = v + 2w(q×v) + 2q×(q×v) 的展开形式，避免构造临时四元数。
		final double tx = 2.0 * (this.y * vz - this.z * vy);
		final double ty = 2.0 * (this.z * vx - this.x * vz);
		final double tz = 2.0 * (this.x * vy - this.y * vx);
		return new double[] {
			vx + this.w * tx + (this.y * tz - this.z * ty),
			vy + this.w * ty + (this.z * tx - this.x * tz),
			vz + this.w * tz + (this.x * ty - this.y * tx)
		};
	}

	/**
	 * 球面线性插值（自动选择最短路径）。
	 *
	 * @param to 目标四元数
	 * @param t 插值系数，0 返回自身，1 返回目标
	 * @return 插值结果
	 */
	public Quat slerp(final Quat to, final double t) {
		double cos = this.dot(to);
		Quat target = to;
		if (cos < 0.0) {
			// 四元数 q 与 -q 表示同一旋转，取反以走最短弧。
			cos = -cos;
			target = new Quat(-to.x, -to.y, -to.z, -to.w);
		}
		if (cos > 0.9995) {
			// 夹角极小时退化为线性插值，避免除以接近零的 sin。
			return new Quat(
				this.x + (target.x - this.x) * t,
				this.y + (target.y - this.y) * t,
				this.z + (target.z - this.z) * t,
				this.w + (target.w - this.w) * t
			).normalize();
		}
		final double theta = Math.acos(Math.min(1.0, cos));
		final double sin = Math.sin(theta);
		final double a = Math.sin((1.0 - t) * theta) / sin;
		final double b = Math.sin(t * theta) / sin;
		return new Quat(
			this.x * a + target.x * b,
			this.y * a + target.y * b,
			this.z * a + target.z * b,
			this.w * a + target.w * b
		).normalize();
	}

	/**
	 * 对数映射：返回旋转向量（单位轴 × 角度，角度位于 [0, π]）。
	 *
	 * @return 旋转向量 {x, y, z}
	 */
	public double[] log() {
		final Quat q = this.w < 0.0 ? new Quat(-this.x, -this.y, -this.z, -this.w) : this;
		final double sinHalf = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
		if (sinHalf < EPSILON) {
			return new double[] {0.0, 0.0, 0.0};
		}
		final double angle = 2.0 * Math.atan2(sinHalf, q.w);
		final double scale = angle / sinHalf;
		return new double[] {q.x * scale, q.y * scale, q.z * scale};
	}

	/**
	 * 旋转角大小（弧度，范围 [0, π]）。
	 *
	 * @return 角度
	 */
	public double angle() {
		final double[] v = this.log();
		return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	/**
	 * 按比例缩放旋转角（四元数幂运算），t=0 为单位旋转，t=1 为自身。
	 *
	 * @param t 比例
	 * @return 缩放后的旋转
	 */
	public Quat scaleAngle(final double t) {
		final double[] v = this.log();
		return exp(v[0] * t, v[1] * t, v[2] * t);
	}

	/**
	 * 分解为 Z-X-Y 顺序的欧拉角。
	 *
	 * @return {偏航, 俯仰, 横滚}（弧度）
	 */
	public double[] toEulerZXY() {
		final Quat q = this.normalize();
		// 旋转矩阵中需要的元素（行列从 0 开始）。
		final double m01 = 2.0 * (q.x * q.y - q.w * q.z);
		final double m11 = 1.0 - 2.0 * (q.x * q.x + q.z * q.z);
		final double m21 = 2.0 * (q.y * q.z + q.w * q.x);
		final double m20 = 2.0 * (q.x * q.z - q.w * q.y);
		final double m22 = 1.0 - 2.0 * (q.x * q.x + q.y * q.y);
		final double pitch = Math.asin(Math.clamp(m21, -1.0, 1.0));
		final double yaw = Math.atan2(-m01, m11);
		final double roll = Math.atan2(-m20, m22);
		return new double[] {yaw, pitch, roll};
	}

	/**
	 * 仅提取偏航分量（按前向向量在水平面上的投影计算）。
	 *
	 * @return 偏航角（弧度，左转为正）
	 */
	public double yaw() {
		final double[] forward = this.rotate(0.0, 1.0, 0.0);
		return Math.atan2(-forward[0], forward[1]);
	}
}
