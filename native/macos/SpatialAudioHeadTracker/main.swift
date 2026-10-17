// SpatialAudio Head Tracker —— AirPods 头部追踪助手
//
// 作用：读取 AirPods / Beats 的头部姿态（CoreMotion 的 CMHeadphoneMotionManager），
// 通过本机 UDP 以 SAHT 协议发送给 Minecraft 中的 SpatialAudio 模组。
//
// 为什么是独立程序：Apple 要求使用耳机运动数据的进程在 Info.plist 中声明 NSMotionUsageDescription，
// 否则系统会直接终止该进程。Minecraft 的 Java 进程无法满足这一点，因此由这个带完整 Info.plist 的
// 小型 .app 负责读取数据，游戏端只接收 UDP 数据包。
//
// 命令行参数：
//   --port <端口>        数据发送目标端口（127.0.0.1），默认 4242
//   --parent-pid <PID>   游戏进程 PID，游戏退出后助手自动退出
//   --self-test          只打印可用性与授权状态并校验数据包格式，不请求权限

import CoreMotion
import Darwin
import Foundation

// MARK: - 协议常量（必须与 Java 端 SahtProtocol 保持一致）

/// SAHT 协议常量。
enum Saht {
    /// 数据包长度（字节）。
    static let packetSize = 88
    /// 魔数 "SAHT"。
    static let magic: [UInt8] = [0x53, 0x41, 0x48, 0x54]
    /// 协议版本。
    static let version: UInt8 = 1
    /// 包类型：姿态。
    static let typePose: UInt8 = 1
    /// 包类型：状态。
    static let typeStatus: UInt8 = 2
    /// 标志位：姿态有效。
    static let flagPoseValid: UInt16 = 1
    /// 标志位：包含角速度。
    static let flagHasRate: UInt16 = 1 << 1
    /// 状态：启动中。
    static let statusStarting: Int32 = 0
    /// 状态：等待耳机连接或佩戴。
    static let statusWaitingForHeadphones: Int32 = 1
    /// 状态：正在输出数据。
    static let statusActive: Int32 = 2
    /// 状态：系统不支持耳机运动数据。
    static let statusNotAvailable: Int32 = 3
    /// 状态：用户拒绝授权。
    static let statusNotAuthorized: Int32 = 4
    /// 状态：运动数据出错。
    static let statusError: Int32 = 5
    /// 状态：等待用户授权。
    static let statusAwaitingAuthorization: Int32 = 6
}

// MARK: - 数据包编码

/// 按小端序依次写入字段的数据包构造器。
struct PacketWriter {
    /// 已写入的数据。
    private(set) var data = Data(capacity: Saht.packetSize)

    /// 写入整数（小端序）。
    mutating func append<T: FixedWidthInteger>(_ value: T) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }

    /// 写入双精度浮点数（IEEE 754，小端序）。
    mutating func append(_ value: Double) {
        append(value.bitPattern)
    }

    /// 写入原始字节。
    mutating func appendBytes(_ bytes: [UInt8]) {
        data.append(contentsOf: bytes)
    }
}

/// 一帧数据包的内容。
struct PacketFields {
    var type: UInt8
    var flags: UInt16
    var status: Int32
    var sequence: UInt32
    var timestamp: Double
    var quaternion: (x: Double, y: Double, z: Double, w: Double)
    var rotationRate: (x: Double, y: Double, z: Double)
    var authorization: Int32
}

/// 按 SAHT 布局编码数据包。
func encodePacket(_ f: PacketFields) -> Data {
    var writer = PacketWriter()
    writer.appendBytes(Saht.magic)
    writer.append(Saht.version)
    writer.append(f.type)
    writer.append(f.flags)
    writer.append(f.status)
    writer.append(f.sequence)
    writer.append(f.timestamp)
    writer.append(f.quaternion.x)
    writer.append(f.quaternion.y)
    writer.append(f.quaternion.z)
    writer.append(f.quaternion.w)
    writer.append(f.rotationRate.x)
    writer.append(f.rotationRate.y)
    writer.append(f.rotationRate.z)
    writer.append(Int32(getpid()))
    writer.append(f.authorization)
    return writer.data
}

// MARK: - 命令行参数

/// 命令行参数。
struct Options {
    /// 目标端口。
    var port: UInt16 = 4242
    /// 游戏进程 PID。
    var parentPid: pid_t = 0
    /// 是否只做自检。
    var selfTest = false

    /// 解析命令行参数，未知参数忽略（open 命令可能附加 -psn_ 等参数）。
    static func parse(_ arguments: [String]) -> Options {
        var options = Options()
        var index = 1
        while index < arguments.count {
            let argument = arguments[index]
            let next = index + 1 < arguments.count ? arguments[index + 1] : nil
            switch argument {
            case "--port":
                if let value = next, let port = UInt16(value), port > 0 {
                    options.port = port
                }
                index += 1
            case "--parent-pid":
                if let value = next, let pid = Int32(value) {
                    options.parentPid = pid
                }
                index += 1
            case "--self-test":
                options.selfTest = true
            default:
                break
            }
            index += 1
        }
        return options
    }
}

// MARK: - UDP 发送

/// 向 127.0.0.1 指定端口发送 UDP 数据报。
final class UdpSender {
    /// 套接字描述符。
    private let socketFd: Int32
    /// 目标地址。
    private let address: sockaddr_in

    /// 创建发送器，失败时返回 nil。
    init?(port: UInt16) {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else {
            return nil
        }
        var target = sockaddr_in()
        target.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        target.sin_family = sa_family_t(AF_INET)
        target.sin_port = port.bigEndian
        target.sin_addr.s_addr = inet_addr("127.0.0.1")
        socketFd = fd
        address = target
    }

    deinit {
        close(socketFd)
    }

    /// 发送一个数据报（失败时静默忽略，接收端可能尚未启动）。
    func send(_ data: Data) {
        var target = address
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            withUnsafePointer(to: &target) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
                    _ = sendto(socketFd, raw.baseAddress, raw.count, 0, socketAddress, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }
}

// MARK: - 头部追踪

/// 读取耳机运动数据并转发。
final class HeadTracker: NSObject, CMHeadphoneMotionManagerDelegate {
    /// CoreMotion 耳机运动管理器。
    private let manager = CMHeadphoneMotionManager()
    /// UDP 发送器。
    private let sender: UdpSender
    /// 运动数据回调队列（串行）。
    private let motionQueue = OperationQueue()
    /// 保护共享状态的锁。
    private let lock = NSLock()
    /// 数据包序号。
    private var sequence: UInt32 = 0
    /// 最近一次收到运动数据的系统运行时间（秒）。
    private var lastMotionUptime: TimeInterval = 0
    /// 最近一次错误描述。
    private var lastError: String?
    /// 耳机是否已连接。
    private var connected = false

    /// 创建追踪器。
    init(sender: UdpSender) {
        self.sender = sender
        super.init()
        motionQueue.name = "SpatialAudio.HeadTracker.Motion"
        motionQueue.maxConcurrentOperationCount = 1
    }

    /// 开始接收耳机连接状态与运动数据。首次调用时 macOS 会弹出“运动与健身”授权请求。
    func start() {
        manager.delegate = self
        guard manager.isDeviceMotionAvailable else {
            logMessage("当前系统不支持耳机运动数据")
            return
        }
        manager.startConnectionStatusUpdates()
        manager.startDeviceMotionUpdates(to: motionQueue) { [weak self] motion, error in
            guard let self = self else {
                return
            }
            if let error = error {
                self.lock.lock()
                self.lastError = error.localizedDescription
                self.lock.unlock()
                return
            }
            if let motion = motion {
                self.publish(motion)
            }
        }
        logMessage("已开始接收耳机运动数据")
    }

    /// 发送一帧姿态数据。
    private func publish(_ motion: CMDeviceMotion) {
        let quaternion = motion.attitude.quaternion
        let rate = motion.rotationRate
        lock.lock()
        sequence &+= 1
        let currentSequence = sequence
        lastMotionUptime = ProcessInfo.processInfo.systemUptime
        lastError = nil
        lock.unlock()
        let packet = encodePacket(PacketFields(
            type: Saht.typePose,
            flags: Saht.flagPoseValid | Saht.flagHasRate,
            status: Saht.statusActive,
            sequence: currentSequence,
            timestamp: motion.timestamp,
            quaternion: (quaternion.x, quaternion.y, quaternion.z, quaternion.w),
            rotationRate: (rate.x, rate.y, rate.z),
            authorization: Int32(truncatingIfNeeded: CMHeadphoneMotionManager.authorizationStatus().rawValue)
        ))
        sender.send(packet)
    }

    /// 计算当前状态码。
    func currentStatus() -> Int32 {
        if !manager.isDeviceMotionAvailable {
            return Saht.statusNotAvailable
        }
        let authorization = CMHeadphoneMotionManager.authorizationStatus()
        if authorization == .denied || authorization == .restricted {
            return Saht.statusNotAuthorized
        }
        lock.lock()
        let since = ProcessInfo.processInfo.systemUptime - lastMotionUptime
        let hasMotion = lastMotionUptime > 0
        let error = lastError
        lock.unlock()
        if hasMotion && since < 1.0 {
            return Saht.statusActive
        }
        if authorization == .notDetermined {
            return Saht.statusAwaitingAuthorization
        }
        if error != nil {
            return Saht.statusError
        }
        return Saht.statusWaitingForHeadphones
    }

    /// 发送状态包（不含姿态），让游戏端知道助手仍在运行以及当前状态。
    func sendStatus() {
        lock.lock()
        sequence &+= 1
        let currentSequence = sequence
        lock.unlock()
        let packet = encodePacket(PacketFields(
            type: Saht.typeStatus,
            flags: 0,
            status: currentStatus(),
            sequence: currentSequence,
            timestamp: ProcessInfo.processInfo.systemUptime,
            quaternion: (0, 0, 0, 1),
            rotationRate: (0, 0, 0),
            authorization: Int32(truncatingIfNeeded: CMHeadphoneMotionManager.authorizationStatus().rawValue)
        ))
        sender.send(packet)
    }

    /// 耳机连接回调。
    func headphoneMotionManagerDidConnect(_ manager: CMHeadphoneMotionManager) {
        lock.lock()
        connected = true
        lock.unlock()
        logMessage("耳机已连接")
    }

    /// 耳机断开回调。
    func headphoneMotionManagerDidDisconnect(_ manager: CMHeadphoneMotionManager) {
        lock.lock()
        connected = false
        lock.unlock()
        logMessage("耳机已断开")
    }
}

// MARK: - 工具函数

/// 输出带前缀的日志到标准错误。
func logMessage(_ message: String) {
    FileHandle.standardError.write(("[SpatialAudioHeadTracker] " + message + "\n").data(using: .utf8) ?? Data())
}

/// 判断父进程是否仍然存在。
func parentAlive(_ pid: pid_t) -> Bool {
    if pid <= 0 {
        return true
    }
    return kill(pid, 0) == 0 || errno != ESRCH
}

// MARK: - 程序入口

let options = Options.parse(CommandLine.arguments)

if options.selfTest {
    // 自检：不启动运动更新，因此不会触发授权弹窗，可在 CI 中运行。
    let manager = CMHeadphoneMotionManager()
    let sample = encodePacket(PacketFields(
        type: Saht.typeStatus,
        flags: 0,
        status: Saht.statusStarting,
        sequence: 1,
        timestamp: 0,
        quaternion: (0, 0, 0, 1),
        rotationRate: (0, 0, 0),
        authorization: -1
    ))
    print("SpatialAudioHeadTracker 自检")
    print("deviceMotionAvailable=\(manager.isDeviceMotionAvailable)")
    print("authorizationStatus=\(CMHeadphoneMotionManager.authorizationStatus().rawValue)")
    print("packetSize=\(sample.count)")
    exit(sample.count == Saht.packetSize && Array(sample.prefix(4)) == Saht.magic ? 0 : 1)
}

guard let sender = UdpSender(port: options.port) else {
    logMessage("无法创建 UDP 套接字")
    exit(1)
}

let tracker = HeadTracker(sender: sender)
tracker.start()

// 每 0.5 秒发送一次状态，并在游戏进程退出后自动退出。
let statusTimer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
statusTimer.schedule(deadline: .now(), repeating: .milliseconds(500))
statusTimer.setEventHandler {
    tracker.sendStatus()
    if !parentAlive(options.parentPid) {
        logMessage("游戏进程已退出，助手结束")
        exit(0)
    }
}
statusTimer.resume()

signal(SIGTERM) { _ in
    exit(0)
}

logMessage("已启动：目标端口 \(options.port)，父进程 \(options.parentPid)")
RunLoop.main.run()
