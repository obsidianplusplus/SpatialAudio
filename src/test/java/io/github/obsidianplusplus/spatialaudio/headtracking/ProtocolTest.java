package io.github.obsidianplusplus.spatialaudio.headtracking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.obsidianplusplus.spatialaudio.math.Quat;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * SAHT 与 OpenTrack 协议、UDP 接收器测试。
 */
class ProtocolTest {
	/**
	 * SAHT 编码长度、魔数与往返解析。
	 */
	@Test
	void sahtRoundTrip() {
		final Quat rotation = Quat.fromEulerZXY(0.3, -0.1, 0.05);
		final SahtProtocol.Packet packet = new SahtProtocol.Packet(SahtProtocol.TYPE_POSE,
			SahtProtocol.FLAG_POSE_VALID | SahtProtocol.FLAG_HAS_RATE, SahtProtocol.STATUS_ACTIVE, 4_000_000_000L, 1234.5,
			rotation, new double[] {0.1, -0.2, 0.3}, 4321, 3);
		final byte[] bytes = SahtProtocol.encode(packet);
		assertEquals(SahtProtocol.PACKET_SIZE, bytes.length);
		assertArrayEquals(new byte[] {'S', 'A', 'H', 'T'}, new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]});
		final SahtProtocol.Packet decoded = SahtProtocol.decode(ByteBuffer.wrap(bytes), bytes.length);
		assertNotNull(decoded);
		assertTrue(decoded.hasPose());
		assertTrue(decoded.hasRate());
		assertEquals(4_000_000_000L, decoded.sequence());
		assertEquals(1234.5, decoded.timestamp(), 1.0e-12);
		assertEquals(rotation.x(), decoded.rotation().x(), 1.0e-12);
		assertEquals(rotation.w(), decoded.rotation().w(), 1.0e-12);
		assertArrayEquals(new double[] {0.1, -0.2, 0.3}, decoded.rotationRate(), 1.0e-12);
		assertEquals(4321, decoded.helperPid());
		assertEquals(3, decoded.authorization());
	}

	/**
	 * 状态包不携带姿态；错误长度或版本被拒绝。
	 */
	@Test
	void sahtRejectsInvalid() {
		final SahtProtocol.Packet status = new SahtProtocol.Packet(SahtProtocol.TYPE_STATUS, 0,
			SahtProtocol.STATUS_NOT_AUTHORIZED, 1, 0.0, Quat.IDENTITY, null, 1, 2);
		final byte[] bytes = SahtProtocol.encode(status);
		final SahtProtocol.Packet decoded = SahtProtocol.decode(ByteBuffer.wrap(bytes), bytes.length);
		assertNotNull(decoded);
		assertFalse(decoded.hasPose());
		assertNull(SahtProtocol.decode(ByteBuffer.wrap(bytes), 40));
		bytes[4] = 9;
		assertNull(SahtProtocol.decode(ByteBuffer.wrap(bytes), bytes.length));
	}

	/**
	 * OpenTrack 约定向右转头偏航为正，转换到内部约定后偏航为负。
	 */
	@Test
	void openTrackYawConvention() {
		final byte[] bytes = OpenTrackProtocol.encode(1.0, 2.0, -3.0, 30.0, 10.0, 0.0);
		final OpenTrackProtocol.Pose pose = OpenTrackProtocol.decode(ByteBuffer.wrap(bytes), bytes.length);
		assertNotNull(pose);
		final double[] euler = pose.rotation().toEulerZXY();
		assertEquals(-30.0, Math.toDegrees(euler[0]), 1.0e-6);
		assertEquals(10.0, Math.toDegrees(euler[1]), 1.0e-6);
		assertArrayEquals(new double[] {0.01, 0.02, -0.03}, pose.translation(), 1.0e-12);
	}

	/**
	 * OpenTrack 异常数值与错误长度被拒绝；带帧号的 52 字节包可以解析。
	 */
	@Test
	void openTrackValidation() {
		final byte[] nan = OpenTrackProtocol.encode(0.0, 0.0, 0.0, Double.NaN, 0.0, 0.0);
		assertNull(OpenTrackProtocol.decode(ByteBuffer.wrap(nan), nan.length));
		final byte[] ok = OpenTrackProtocol.encode(0.0, 0.0, 0.0, 5.0, 0.0, 0.0);
		assertNull(OpenTrackProtocol.decode(ByteBuffer.wrap(ok), 47));
		final byte[] withFrame = new byte[52];
		System.arraycopy(ok, 0, withFrame, 0, 48);
		assertNotNull(OpenTrackProtocol.decode(ByteBuffer.wrap(withFrame), 52));
	}

	/**
	 * UDP 接收器能在回环地址上收到两种格式的数据包。
	 *
	 * @throws Exception 网络异常
	 */
	@Test
	void receiverDispatchesBothFormats() throws Exception {
		final CountDownLatch latch = new CountDownLatch(2);
		final AtomicReference<SahtProtocol.Packet> saht = new AtomicReference<>();
		final AtomicReference<OpenTrackProtocol.Pose> openTrack = new AtomicReference<>();
		final UdpPoseReceiver receiver = new UdpPoseReceiver(0, false, new UdpPoseReceiver.Listener() {
			@Override
			public void onSahtPacket(final SahtProtocol.Packet packet, final long receivedNanos) {
				saht.set(packet);
				latch.countDown();
			}

			@Override
			public void onOpenTrackPacket(final OpenTrackProtocol.Pose pose, final long receivedNanos) {
				openTrack.set(pose);
				latch.countDown();
			}
		});
		receiver.start();
		try (DatagramSocket sender = new DatagramSocket()) {
			final InetAddress loopback = InetAddress.getLoopbackAddress();
			final byte[] a = SahtProtocol.encode(new SahtProtocol.Packet(SahtProtocol.TYPE_POSE, SahtProtocol.FLAG_POSE_VALID,
				SahtProtocol.STATUS_ACTIVE, 7, 1.0, Quat.IDENTITY, null, 1, 3));
			final byte[] b = OpenTrackProtocol.encode(0.0, 0.0, 0.0, 12.0, 0.0, 0.0);
			sender.send(new DatagramPacket(a, a.length, loopback, receiver.port()));
			sender.send(new DatagramPacket(b, b.length, loopback, receiver.port()));
			assertTrue(latch.await(5, TimeUnit.SECONDS), "应在 5 秒内收到两个数据包");
		} finally {
			receiver.close();
		}
		assertEquals(7, saht.get().sequence());
		assertEquals(-12.0, Math.toDegrees(openTrack.get().rotation().toEulerZXY()[0]), 1.0e-6);
	}
}
