package io.github.obsidianplusplus.spatialaudio.headtracking;

import io.github.obsidianplusplus.spatialaudio.SpatialAudio;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/**
 * 在后台线程监听 UDP 端口，接收 SAHT（AirPods 助手）与 OpenTrack 两种格式的头部姿态数据包。
 *
 * <p>默认只绑定本机回环地址，外部设备无法向游戏注入数据；如需接收局域网内其它设备的 OpenTrack 数据，
 * 可在配置中开启“监听所有网卡”。
 */
public final class UdpPoseReceiver implements Runnable {
	/** 接收超时（毫秒），用于定期检查停止标志。 */
	private static final int RECEIVE_TIMEOUT_MS = 500;

	/** 数据包回调。 */
	public interface Listener {
		/**
		 * 收到 SAHT 数据包。
		 *
		 * @param packet 解析结果
		 * @param receivedNanos 接收时刻
		 */
		void onSahtPacket(SahtProtocol.Packet packet, long receivedNanos);

		/**
		 * 收到 OpenTrack 数据包。
		 *
		 * @param pose 解析结果
		 * @param receivedNanos 接收时刻
		 */
		void onOpenTrackPacket(OpenTrackProtocol.Pose pose, long receivedNanos);
	}

	/** 数据包回调。 */
	private final Listener listener;

	/** UDP 套接字。 */
	private final DatagramSocket socket;

	/** 接收线程。 */
	private final Thread thread;

	/** 运行标志。 */
	private volatile boolean running = true;

	/** 无法识别的数据包计数（用于调试）。 */
	private volatile long unknownPackets;

	/**
	 * 创建并绑定接收器（尚未启动线程）。
	 *
	 * @param port 端口，0 表示由系统分配临时端口
	 * @param allInterfaces 是否监听所有网卡
	 * @param listener 数据包回调
	 * @throws SocketException 端口被占用或无法绑定时抛出
	 */
	public UdpPoseReceiver(final int port, final boolean allInterfaces, final Listener listener) throws SocketException {
		this.listener = listener;
		final InetAddress address = allInterfaces ? null : InetAddress.getLoopbackAddress();
		final DatagramSocket created = new DatagramSocket(null);
		try {
			created.setReuseAddress(false);
			created.bind(address == null ? new InetSocketAddress(port) : new InetSocketAddress(address, port));
			created.setSoTimeout(RECEIVE_TIMEOUT_MS);
		} catch (SocketException e) {
			created.close();
			throw e;
		}
		this.socket = created;
		this.thread = new Thread(this, "SpatialAudio-UDP-Receiver");
		this.thread.setDaemon(true);
	}

	/**
	 * 启动接收线程。
	 */
	public void start() {
		this.thread.start();
	}

	/**
	 * 实际绑定的端口。
	 *
	 * @return 端口号
	 */
	public int port() {
		return this.socket.getLocalPort();
	}

	/**
	 * 无法识别的数据包数量。
	 *
	 * @return 计数
	 */
	public long unknownPackets() {
		return this.unknownPackets;
	}

	/**
	 * 停止接收并关闭套接字。
	 */
	public void close() {
		this.running = false;
		this.socket.close();
		try {
			this.thread.join(1000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void run() {
		final byte[] data = new byte[512];
		final DatagramPacket packet = new DatagramPacket(data, data.length);
		while (this.running) {
			try {
				packet.setLength(data.length);
				this.socket.receive(packet);
				this.dispatch(ByteBuffer.wrap(data, 0, packet.getLength()).slice(), packet.getLength(), System.nanoTime());
			} catch (SocketTimeoutException ignored) {
				// 超时只是为了定期检查运行标志，继续等待即可。
			} catch (IOException e) {
				if (this.running) {
					SpatialAudio.LOGGER.warn("头部追踪 UDP 接收出错：{}", e.toString());
				}
			} catch (RuntimeException e) {
				SpatialAudio.LOGGER.error("处理头部追踪数据包时发生异常", e);
			}
		}
	}

	/**
	 * 根据长度与魔数分发数据包。
	 *
	 * @param buffer 数据
	 * @param length 长度
	 * @param receivedNanos 接收时刻
	 */
	private void dispatch(final ByteBuffer buffer, final int length, final long receivedNanos) {
		if (SahtProtocol.matches(buffer, length)) {
			final SahtProtocol.Packet saht = SahtProtocol.decode(buffer, length);
			if (saht != null) {
				this.listener.onSahtPacket(saht, receivedNanos);
				return;
			}
		} else if (OpenTrackProtocol.matches(length)) {
			final OpenTrackProtocol.Pose pose = OpenTrackProtocol.decode(buffer, length);
			if (pose != null) {
				this.listener.onOpenTrackPacket(pose, receivedNanos);
				return;
			}
		}
		this.unknownPackets++;
	}
}
