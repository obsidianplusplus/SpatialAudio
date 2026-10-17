// SpatialAudio Head Tracker —— AirPods 头部追踪与 Apple 空间音频渲染助手
//
// 作用一（头部追踪，模组自带渲染器使用）：读取 AirPods / Beats 的头部姿态（CoreMotion 的
// CMHeadphoneMotionManager），通过本机 UDP 以 SAHT 协议发送给 Minecraft 中的 SpatialAudio 模组。
//
// 作用二（Apple 空间音频渲染，可选）：当模组的“空间音频渲染方式”设为 Apple 时，模组把 OpenAL Soft
// 回环设备渲染出的多声道声床（默认三阶 Ambisonics ACN/SN3D 共 16 声道，或 7.1）通过本机 TCP 以
// SAAB 协议推送给本助手。助手用 AVAudioEngine 播放：AVAudioSourceNode（sourceMode 为 ambienceBed，
// 渲染算法为 .auto）→ AVAudioEnvironmentNode（outputType 为 .auto）→ 主混音器 → 输出设备，
// 由 Apple 的空间音频渲染器完成双耳化。头部旋转在助手内部作用于环境节点的听者朝向：默认使用模组
// 处理过的头部姿态（HEAD 消息）；若本程序带有 com.apple.developer.coremotion.head-pose 授权且系统为
// macOS 15 及以上，可改用 Apple 自动头部追踪（isListenerHeadTrackingEnabled）。随附的 ad-hoc 签名版本
// 不声明任何受限授权；带授权的版本需要用户自行使用付费开发者账号签名。
//
// 为什么是独立程序：Apple 要求使用耳机运动数据的进程在 Info.plist 中声明 NSMotionUsageDescription，
// 否则系统会直接终止该进程。Minecraft 的 Java 进程无法满足这一点，因此由这个带完整 Info.plist 的
// 小型 .app 负责读取数据，游戏端只接收 UDP 数据包；Apple 空间音频渲染同样只能由原生程序调用。
//
// 命令行参数：
//   --port <端口>          SAHT 数据发送目标端口（127.0.0.1），默认 4242；为 0 时不启动 CoreMotion、不发送 SAHT
//   --parent-pid <PID>     游戏进程 PID，游戏退出后助手自动退出
//   --audio-port <端口>    SAAB 音频桥的 TCP 端口（127.0.0.1）；提供后启动音频桥客户端与 Apple 空间音频渲染器
//   --audio-token <令牌>   SAAB 握手令牌（32 个小写十六进制字符），必须与 --audio-port 一起提供
//   --self-test            只打印可用性与授权状态、校验 SAHT/SAAB 数据格式与环形缓冲区，并以离线手动渲染
//                          检查 Apple 空间音频声床的方向；不请求权限、不启动运动更新、不需要音频设备，
//                          最后输出 selfTest=PASS 或 selfTest=FAIL（失败时退出码非 0）

import AVFAudio
import AudioToolbox
import CoreAudio
import CoreMotion
import Darwin
import Foundation
import Security
import os

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
    /// 包类型。
    var type: UInt8
    /// 标志位。
    var flags: UInt16
    /// 状态码。
    var status: Int32
    /// 序号。
    var sequence: UInt32
    /// 时间戳（秒）。
    var timestamp: Double
    /// 姿态四元数。
    var quaternion: (x: Double, y: Double, z: Double, w: Double)
    /// 角速度（弧度/秒）。
    var rotationRate: (x: Double, y: Double, z: Double)
    /// CoreMotion 授权状态。
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
    /// SAHT 目标端口；为 0 时不启动 CoreMotion，也不发送 SAHT。
    var port: UInt16 = 4242
    /// 游戏进程 PID。
    var parentPid: pid_t = 0
    /// 是否只做自检。
    var selfTest = false
    /// SAAB 音频桥端口；为 0 时不启动 Apple 空间音频渲染。
    var audioPort: UInt16 = 0
    /// SAAB 握手令牌。
    var audioToken: String?

    /// 解析命令行参数，未知参数忽略（open 命令可能附加 -psn_ 等参数）。
    static func parse(_ arguments: [String]) -> Options {
        var options = Options()
        var index = 1
        while index < arguments.count {
            let argument = arguments[index]
            let next = index + 1 < arguments.count ? arguments[index + 1] : nil
            switch argument {
            case "--port":
                // 允许 0：表示只做音频渲染，不读取耳机运动数据。
                if let value = next, let port = UInt16(value) {
                    options.port = port
                }
                index += 1
            case "--parent-pid":
                if let value = next, let pid = Int32(value) {
                    options.parentPid = pid
                }
                index += 1
            case "--audio-port":
                if let value = next, let port = UInt16(value) {
                    options.audioPort = port
                }
                index += 1
            case "--audio-token":
                if let value = next {
                    options.audioToken = value
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

    /// 判断令牌是否为 32 个小写十六进制字符。
    static func isValidToken(_ token: String) -> Bool {
        let bytes = Array(token.utf8)
        guard bytes.count == Saab.tokenLength else {
            return false
        }
        return bytes.allSatisfy { ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x66) }
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

// MARK: - SAAB 协议（SpatialAudio Audio Bridge v1，必须与 Java 端 AppleBridgeProtocol 保持一致）

/// SAAB 协议常量。
enum Saab {
    /// 魔数 "SAAB"。
    static let magic: [UInt8] = [0x53, 0x41, 0x41, 0x42]
    /// 帧头长度（字节）。
    static let headerSize = 12
    /// 单帧负载上限（字节）。
    static let maxPayload = 4 * 1024 * 1024
    /// 协议版本。
    static let version: UInt32 = 1
    /// 令牌长度（ASCII 字节）。
    static let tokenLength = 32
    /// HELLO 负载长度（字节）。
    static let helloPayloadSize = 48
    /// HEAD 负载长度（字节）。
    static let headPayloadSize = 20
    /// CONFIG 负载的最小长度（不含设备名，字节）。
    static let configMinimumSize = 22

    /// 消息类型：握手（助手 → 模组）。
    static let typeHello: UInt16 = 1
    /// 消息类型：配置（模组 → 助手）。
    static let typeConfig: UInt16 = 2
    /// 消息类型：音频数据（模组 → 助手）。
    static let typeAudio: UInt16 = 3
    /// 消息类型：请求音频帧（助手 → 模组）。
    static let typeRequest: UInt16 = 4
    /// 消息类型：头部姿态（模组 → 助手）。
    static let typeHead: UInt16 = 5
    /// 消息类型：状态（助手 → 模组）。
    static let typeStatus: UInt16 = 6

    /// 能力位：支持 Apple 自动头部追踪（macOS 15+ 且带 head-pose 授权）。
    static let capabilityAutoHeadTracking: UInt32 = 1
    /// 能力位：带个性化空间音频授权（profile-access）。
    static let capabilityPersonalizedProfile: UInt32 = 1 << 1
    /// 能力位：系统支持耳机运动数据。
    static let capabilityMotionAvailable: UInt32 = 1 << 2

    /// 声床布局：无（空闲）。
    static let layoutNone: UInt16 = 0
    /// 声床布局：高阶 Ambisonics（ACN 排序、SN3D 归一化）。
    static let layoutHoaAcnSn3d: UInt16 = 1
    /// 声床布局：7.1（OpenAL 顺序 FL FR FC LFE BL BR SL SR，即 WAVE 7.1）。
    static let layoutWave71: UInt16 = 2

    /// CONFIG 标志位：优先使用 Apple 自动头部追踪。
    static let configPreferAutoHeadTracking: UInt32 = 1
    /// HEAD 标志位：姿态有效。
    static let headValid: UInt32 = 1

    /// STATUS 标志位：音频引擎正在运行。
    static let statusEngineRunning: UInt32 = 1
    /// STATUS 标志位：Apple 自动头部追踪已启用。
    static let statusAutoHeadTrackingActive: UInt32 = 1 << 1
    /// STATUS 标志位：输出设备推测为耳机。
    static let statusHeadphones: UInt32 = 1 << 2
    /// STATUS 标志位：声床已按 ambienceBed 接入且 .auto 渲染算法可用。
    static let statusBedAccepted: UInt32 = 1 << 3
    /// STATUS 标志位：音频引擎出错。
    static let statusEngineError: UInt32 = 1 << 4

    /// 单次 REQUEST 的最少帧数。
    static let minimumRequestFrames = 128
    /// 目标缓冲帧数的下限。
    static let minimumTargetFrames = 256
    /// 已请求但长时间无回应时，重置未完成请求计数的超时（秒）。
    static let staleCreditSeconds: TimeInterval = 1.0
    /// 状态消息发送间隔（毫秒）。
    static let statusIntervalMilliseconds = 250
    /// 连接失败或断开后的重试间隔（秒）。
    static let reconnectSeconds: TimeInterval = 1.0
    /// 连续多少次连接刚建立就被关闭后认定本实例已过时并退出。
    static let maxRejectedConnections = 5
    /// 请求循环的周期（微秒）。
    static let creditLoopMicroseconds: UInt32 = 2000

    /// Apple 自动头部追踪所需的受限授权。
    static let entitlementHeadPose = "com.apple.developer.coremotion.head-pose"
    /// 个性化空间音频所需的受限授权。
    static let entitlementProfileAccess = "com.apple.developer.spatial-audio.profile-access"
}

extension PacketWriter {
    /// 写入单精度浮点数（IEEE 754，小端序）。
    mutating func appendFloat(_ value: Float) {
        append(value.bitPattern)
    }

    /// 追加一段已编码的数据。
    mutating func appendData(_ other: Data) {
        data.append(other)
    }

    /// 写入“u16 字节长度 + UTF-8 字节”格式的字符串，超长时按完整字符截断。
    mutating func appendString16(_ text: String, maxBytes: Int = 1024) {
        var bytes: [UInt8] = []
        for scalar in text.unicodeScalars {
            let encoded = Array(String(scalar).utf8)
            if bytes.count + encoded.count > maxBytes {
                break
            }
            bytes.append(contentsOf: encoded)
        }
        append(UInt16(bytes.count))
        appendBytes(bytes)
    }
}

/// 按小端序依次读取字段的只读游标，所有读取都做边界检查。
struct ByteReader {
    /// 被读取的字节。
    let buffer: UnsafeRawBufferPointer
    /// 当前读取位置。
    private(set) var offset = 0

    /// 创建游标。
    init(_ buffer: UnsafeRawBufferPointer) {
        self.buffer = buffer
    }

    /// 剩余可读字节数。
    var remaining: Int {
        return buffer.count - offset
    }

    /// 读取 u16，越界时返回 nil。
    mutating func u16() -> UInt16? {
        guard remaining >= 2 else {
            return nil
        }
        let value = UInt16(littleEndian: buffer.loadUnaligned(fromByteOffset: offset, as: UInt16.self))
        offset += 2
        return value
    }

    /// 读取 u32，越界时返回 nil。
    mutating func u32() -> UInt32? {
        guard remaining >= 4 else {
            return nil
        }
        let value = UInt32(littleEndian: buffer.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
        offset += 4
        return value
    }

    /// 读取 f32，越界时返回 nil。
    mutating func f32() -> Float? {
        guard let bits = u32() else {
            return nil
        }
        return Float(bitPattern: bits)
    }

    /// 读取指定数量的原始字节，越界时返回 nil。
    mutating func bytes(count: Int) -> [UInt8]? {
        guard count >= 0, remaining >= count else {
            return nil
        }
        var result = [UInt8](repeating: 0, count: count)
        for index in 0..<count {
            result[index] = buffer[offset + index]
        }
        offset += count
        return result
    }
}

/// CONFIG 消息内容。
struct BridgeConfig: Equatable {
    /// 配置代次（每次 CONFIG 递增）。
    var generation: UInt32
    /// 采样率（Hz）。
    var sampleRate: UInt32
    /// 声道数（0 表示空闲）。
    var channels: UInt16
    /// 声床布局（见 Saab.layout*）。
    var layout: UInt16
    /// 标志位（见 Saab.config*）。
    var flags: UInt32
    /// 目标缓冲帧数（环形缓冲区与未完成请求之和）。
    var targetFrames: UInt32
    /// 输出设备名（空字符串表示系统默认输出设备）。
    var deviceName: String
}

/// HEAD 消息内容（模组头部坐标系：x 右、y 前、z 上）。
struct BridgeHeadPose: Equatable {
    /// 姿态是否有效。
    var valid: Bool
    /// 四元数虚部 x。
    var x: Float
    /// 四元数虚部 y。
    var y: Float
    /// 四元数虚部 z。
    var z: Float
    /// 四元数实部 w。
    var w: Float
}

/// STATUS 消息内容。
struct BridgeStatus {
    /// 状态标志位（见 Saab.status*）。
    var flags: UInt32 = 0
    /// 助手当前运行的配置代次。
    var generation: UInt32 = 0
    /// 环形缓冲区中的帧数。
    var bufferedFrames: UInt32 = 0
    /// 累计欠载次数。
    var underruns: UInt32 = 0
    /// 正在使用的渲染算法原始值。
    var renderingAlgorithm: UInt32 = 0
    /// 输出硬件采样率（Hz）。
    var hardwareSampleRate: UInt32 = 0
    /// 输出延迟（秒，未知时为 0）。
    var outputLatency: Float = 0
    /// 输出设备名。
    var deviceName: String = ""
    /// 最近一次错误信息（无错误时为空）。
    var lastError: String = ""
}

/// SAAB 消息的编码与解码（纯函数，不涉及套接字，便于自检）。
enum SaabCodec {
    /// 为负载加上 12 字节帧头。
    static func frame(type: UInt16, payload: Data) -> Data {
        var writer = PacketWriter()
        writer.appendBytes(Saab.magic)
        writer.append(type)
        writer.append(UInt16(0))
        writer.append(UInt32(payload.count))
        writer.appendData(payload)
        return writer.data
    }

    /// 解析帧头；魔数不符或负载超过上限时返回 nil。
    static func decodeHeader(_ header: UnsafeRawBufferPointer) -> (type: UInt16, length: Int)? {
        guard header.count >= Saab.headerSize else {
            return nil
        }
        for index in 0..<Saab.magic.count where header[index] != Saab.magic[index] {
            return nil
        }
        var reader = ByteReader(header)
        _ = reader.bytes(count: 4)
        guard let type = reader.u16(), reader.u16() != nil, let length = reader.u32() else {
            return nil
        }
        guard Int(length) <= Saab.maxPayload else {
            return nil
        }
        return (type, Int(length))
    }

    /// 编码 HELLO 负载（令牌不足 32 字节时补 0，超出部分截断）。
    static func helloPayload(token: [UInt8], capabilities: UInt32, macOSMajor: UInt32, pid: UInt32) -> Data {
        var tokenBytes = Array(token.prefix(Saab.tokenLength))
        while tokenBytes.count < Saab.tokenLength {
            tokenBytes.append(0)
        }
        var writer = PacketWriter()
        writer.append(Saab.version)
        writer.appendBytes(tokenBytes)
        writer.append(capabilities)
        writer.append(macOSMajor)
        writer.append(pid)
        return writer.data
    }

    /// 编码 REQUEST 负载。
    static func requestPayload(generation: UInt32, frames: UInt32) -> Data {
        var writer = PacketWriter()
        writer.append(generation)
        writer.append(frames)
        return writer.data
    }

    /// 编码 STATUS 负载。
    static func statusPayload(_ status: BridgeStatus) -> Data {
        var writer = PacketWriter()
        writer.append(status.flags)
        writer.append(status.generation)
        writer.append(status.bufferedFrames)
        writer.append(status.underruns)
        writer.append(status.renderingAlgorithm)
        writer.append(status.hardwareSampleRate)
        writer.appendFloat(status.outputLatency)
        writer.appendString16(status.deviceName)
        writer.appendString16(status.lastError)
        return writer.data
    }

    /// 解码 CONFIG 负载；长度不足或设备名越界时返回 nil（多余的尾部字节忽略，便于以后扩展）。
    static func decodeConfig(_ payload: UnsafeRawBufferPointer) -> BridgeConfig? {
        guard payload.count >= Saab.configMinimumSize else {
            return nil
        }
        var reader = ByteReader(payload)
        guard let generation = reader.u32(),
              let sampleRate = reader.u32(),
              let channels = reader.u16(),
              let layout = reader.u16(),
              let flags = reader.u32(),
              let targetFrames = reader.u32(),
              let nameLength = reader.u16(),
              let nameBytes = reader.bytes(count: Int(nameLength)) else {
            return nil
        }
        return BridgeConfig(
            generation: generation,
            sampleRate: sampleRate,
            channels: channels,
            layout: layout,
            flags: flags,
            targetFrames: targetFrames,
            deviceName: String(decoding: nameBytes, as: UTF8.self)
        )
    }

    /// 解码 HEAD 负载；长度不足时返回 nil。
    static func decodeHead(_ payload: UnsafeRawBufferPointer) -> BridgeHeadPose? {
        var reader = ByteReader(payload)
        guard let flags = reader.u32(),
              let x = reader.f32(),
              let y = reader.f32(),
              let z = reader.f32(),
              let w = reader.f32() else {
            return nil
        }
        return BridgeHeadPose(valid: flags & Saab.headValid != 0, x: x, y: y, z: z, w: w)
    }
}

// MARK: - 能力检测

/// 读取本进程代码签名中的授权（entitlement）。
enum Entitlements {
    /// 判断本进程是否带有指定授权（布尔值为 true，或存在非布尔值）。
    static func isPresent(_ name: String) -> Bool {
        guard let task = SecTaskCreateFromSelf(nil) else {
            return false
        }
        guard let value = SecTaskCopyValueForEntitlement(task, name as CFString, nil) else {
            return false
        }
        if let flag = value as? Bool {
            return flag
        }
        return true
    }
}

/// 计算 HELLO 中上报的能力位。
enum BridgeCapabilities {
    /// 检测能力位。只读取授权与可用性，不会触发任何权限请求。
    static func detect() -> UInt32 {
        var flags: UInt32 = 0
        // 两项授权都从 macOS 15 起才生效，更早的系统即使带有授权也不会使用它们。
        if #available(macOS 15.0, *) {
            if Entitlements.isPresent(Saab.entitlementHeadPose) {
                flags |= Saab.capabilityAutoHeadTracking
            }
            if Entitlements.isPresent(Saab.entitlementProfileAccess) {
                flags |= Saab.capabilityPersonalizedProfile
            }
        }
        if CMHeadphoneMotionManager().isDeviceMotionAvailable {
            flags |= Saab.capabilityMotionAvailable
        }
        return flags
    }

    /// 当前 macOS 主版本号。
    static func macOSMajorVersion() -> UInt32 {
        return UInt32(clamping: ProcessInfo.processInfo.operatingSystemVersion.majorVersion)
    }
}

// MARK: - 环形缓冲区（实时安全）

/// 环形缓冲区的共享状态（只在持有锁时读写）。
/// 该结构体存放在手动分配、地址固定的内存中；实时渲染线程只通过裸指针访问它，不涉及引用计数与内存分配。
struct RingState {
    /// 样本存储：按声道分块，声道 c 的第 i 帧位于 c × capacity + i。
    var samples: UnsafeMutablePointer<Float>
    /// 每个声道的容量（帧）。
    var capacity: Int
    /// 当前声道数（0 表示空闲）。
    var channels: Int
    /// 读位置（帧）。
    var readIndex: Int
    /// 已缓冲帧数。
    var count: Int
    /// 开始播放（或欠载后恢复播放）所需的最少缓冲帧数。
    var startThreshold: Int
    /// 是否处于播放状态（否则输出静音，等待缓冲达到阈值）。
    var playing: Bool
    /// 自上次重置以来是否收到过数据。
    var primed: Bool
    /// 累计欠载次数。
    var underruns: UInt32
    /// 音频 IO 线程单次拉取的最大帧数（自上次清零以来，0 表示尚未拉取）。
    var maxPull: Int
}

/// 环形缓冲区句柄：两个创建后不再改变的裸指针，实时渲染闭包只按值捕获它。
/// 锁单独分配，不放在 RingState 里，这样读取锁地址不会与其他线程对状态的修改形成访问冲突。
struct RingHandle {
    /// 互斥锁（os_unfair_lock，单独分配，地址固定，不能是 Swift 管理的值）。
    let lock: UnsafeMutablePointer<os_unfair_lock>
    /// 共享状态。
    let state: UnsafeMutablePointer<RingState>
}

/// 预分配、按声道分块存储的音频环形缓冲区，由 os_unfair_lock 保护。
/// 进程内只创建一次，整个生命周期内不会重新分配，因此实时渲染闭包可以安全地持有其裸指针。
final class AudioRing: @unchecked Sendable {
    /// 支持的最大声道数（三阶 Ambisonics 为 16 声道）。
    static let maxChannels = 16
    /// 每个声道的容量（帧），满足 ≥ max(4 × 目标缓冲, 16384)。
    static let capacityFrames = 65536
    /// 解交错临时缓冲区的容量（帧）。
    static let scratchFrames = 4096

    /// 锁与共享状态的句柄（实时渲染闭包只捕获它）。
    let handle: RingHandle
    /// 解交错临时缓冲区（仅由写入线程使用）。
    private let scratch: UnsafeMutablePointer<Float>

    /// 分配并初始化缓冲区。
    init() {
        let lock = UnsafeMutablePointer<os_unfair_lock>.allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
        let sampleCount = AudioRing.maxChannels * AudioRing.capacityFrames
        let samples = UnsafeMutablePointer<Float>.allocate(capacity: sampleCount)
        samples.initialize(repeating: 0, count: sampleCount)
        let state = UnsafeMutablePointer<RingState>.allocate(capacity: 1)
        state.initialize(to: RingState(
            samples: samples,
            capacity: AudioRing.capacityFrames,
            channels: 0,
            readIndex: 0,
            count: 0,
            startThreshold: 1,
            playing: false,
            primed: false,
            underruns: 0,
            maxPull: 0
        ))
        handle = RingHandle(lock: lock, state: state)
        let scratchCount = AudioRing.maxChannels * AudioRing.scratchFrames
        scratch = UnsafeMutablePointer<Float>.allocate(capacity: scratchCount)
        scratch.initialize(repeating: 0, count: scratchCount)
    }

    deinit {
        // 调用方保证此时已没有任何线程（包括音频渲染线程）在使用该缓冲区。
        let samples = handle.state.pointee.samples
        samples.deinitialize(count: AudioRing.maxChannels * AudioRing.capacityFrames)
        samples.deallocate()
        handle.state.deinitialize(count: 1)
        handle.state.deallocate()
        handle.lock.deinitialize(count: 1)
        handle.lock.deallocate()
        scratch.deinitialize(count: AudioRing.maxChannels * AudioRing.scratchFrames)
        scratch.deallocate()
    }

    /// 清空缓冲区并切换声道数（0 表示空闲）。累计欠载次数保留。
    func reset(channels: Int, startThreshold: Int) {
        let clampedChannels = min(max(channels, 0), AudioRing.maxChannels)
        let clampedThreshold = min(max(startThreshold, 1), AudioRing.capacityFrames)
        let state = handle.state
        os_unfair_lock_lock(handle.lock)
        state.pointee.channels = clampedChannels
        state.pointee.readIndex = 0
        state.pointee.count = 0
        state.pointee.startThreshold = clampedThreshold
        state.pointee.playing = false
        state.pointee.primed = false
        os_unfair_lock_unlock(handle.lock)
    }

    /// 当前缓冲帧数。
    func bufferedFrames() -> Int {
        os_unfair_lock_lock(handle.lock)
        let count = handle.state.pointee.count
        os_unfair_lock_unlock(handle.lock)
        return count
    }

    /// 累计欠载次数。
    func underrunCount() -> UInt32 {
        os_unfair_lock_lock(handle.lock)
        let underruns = handle.state.pointee.underruns
        os_unfair_lock_unlock(handle.lock)
        return underruns
    }

    /// 音频 IO 线程单次拉取的最大帧数（0 表示尚未拉取）。只有渲染线程知道拉取大小，请求循环据此放大目标缓冲。
    func maxPullFrames() -> Int {
        os_unfair_lock_lock(handle.lock)
        let maxPull = handle.state.pointee.maxPull
        os_unfair_lock_unlock(handle.lock)
        return maxPull
    }

    /// 清零单次拉取帧数的统计（音频图重建后输出设备与 IO 缓冲区大小可能已经改变）。
    func resetPullStatistics() {
        os_unfair_lock_lock(handle.lock)
        handle.state.pointee.maxPull = 0
        os_unfair_lock_unlock(handle.lock)
    }

    /// 写入交错的小端 float32 样本；缓冲区满时丢弃最旧的数据。
    /// 先在锁外解交错到临时缓冲区，再加锁拷贝，尽量缩短实时线程等待锁的时间。
    func writeInterleaved(_ source: UnsafeRawPointer, frames: Int, channels: Int) {
        guard channels > 0, channels <= AudioRing.maxChannels, frames > 0 else {
            return
        }
        let state = handle.state
        let bytesPerFrame = channels * MemoryLayout<Float>.size
        var done = 0
        while done < frames {
            let chunk = min(AudioRing.scratchFrames, frames - done)
            let base = source + done * bytesPerFrame
            for frame in 0..<chunk {
                for channel in 0..<channels {
                    let bits = UInt32(littleEndian: base.loadUnaligned(fromByteOffset: (frame * channels + channel) * 4, as: UInt32.self))
                    let value = Float(bitPattern: bits)
                    // 非有限值（NaN/Inf）会污染整个空间渲染器，直接替换为 0。
                    scratch[channel * AudioRing.scratchFrames + frame] = value.isFinite ? value : 0
                }
            }
            os_unfair_lock_lock(handle.lock)
            if state.pointee.channels == channels {
                let capacity = state.pointee.capacity
                let samples = state.pointee.samples
                var readIndex = state.pointee.readIndex
                var count = state.pointee.count
                if count + chunk > capacity {
                    // 溢出：丢弃最旧的帧。
                    let drop = count + chunk - capacity
                    readIndex = (readIndex + drop) % capacity
                    count -= drop
                }
                let writeIndex = (readIndex + count) % capacity
                let first = min(chunk, capacity - writeIndex)
                for channel in 0..<channels {
                    let destination = samples + channel * capacity
                    let origin = scratch + channel * AudioRing.scratchFrames
                    (destination + writeIndex).update(from: origin, count: first)
                    if chunk > first {
                        destination.update(from: origin + first, count: chunk - first)
                    }
                }
                state.pointee.readIndex = readIndex
                state.pointee.count = count + chunk
                state.pointee.primed = true
            }
            os_unfair_lock_unlock(handle.lock)
            done += chunk
        }
    }
}

/// 实时渲染：从环形缓冲区取出 frameCount 帧写入非交错的输出缓冲区列表。
/// 运行在音频 IO 线程上：不分配内存、不打日志、不使用 Swift 集合，也不触发引用计数。
/// 数据不足时用 0 填充剩余部分并记一次欠载，然后等待缓冲重新达到阈值再恢复播放。
func renderFromRing(_ ring: RingHandle, frameCount: Int, outputData: UnsafeMutablePointer<AudioBufferList>, isSilence: UnsafeMutablePointer<ObjCBool>) {
    let buffers = UnsafeMutableAudioBufferListPointer(outputData)
    let bufferCount = buffers.count
    // 每个缓冲区实际可写的帧数，取所有缓冲区与 frameCount 的最小值。
    var limit = max(frameCount, 0)
    for index in 0..<bufferCount {
        limit = min(limit, Int(buffers[index].mDataByteSize) / MemoryLayout<Float>.size)
    }
    let state = ring.state
    os_unfair_lock_lock(ring.lock)
    // 记录单次拉取的最大帧数（只是一次整数比较与写入），请求循环据此保证缓冲至少能覆盖一次拉取。
    if frameCount > state.pointee.maxPull {
        state.pointee.maxPull = frameCount
    }
    let channels = state.pointee.channels
    var copied = 0
    if channels > 0 && limit > 0 {
        // 至少缓冲满一次拉取的帧数才开始播放，否则第一次拉取就会欠载。
        if !state.pointee.playing && state.pointee.primed && state.pointee.count >= max(state.pointee.startThreshold, frameCount) {
            state.pointee.playing = true
        }
        if state.pointee.playing {
            copied = min(limit, state.pointee.count)
            if copied < frameCount {
                state.pointee.underruns &+= 1
                state.pointee.playing = false
            }
            if copied > 0 {
                let capacity = state.pointee.capacity
                let samples = state.pointee.samples
                let readIndex = state.pointee.readIndex
                let first = min(copied, capacity - readIndex)
                let copyChannels = min(channels, bufferCount)
                for index in 0..<copyChannels {
                    guard let data = buffers[index].mData else {
                        continue
                    }
                    let output = data.assumingMemoryBound(to: Float.self)
                    let origin = samples + index * capacity
                    output.update(from: origin + readIndex, count: first)
                    if copied > first {
                        (output + first).update(from: origin, count: copied - first)
                    }
                }
                state.pointee.readIndex = (readIndex + copied) % capacity
                state.pointee.count -= copied
            }
        }
    }
    os_unfair_lock_unlock(ring.lock)
    // 锁外补零：未拷贝的帧以及多出来的声道。
    for index in 0..<bufferCount {
        guard let data = buffers[index].mData else {
            continue
        }
        let output = data.assumingMemoryBound(to: Float.self)
        let start = index < channels ? copied : 0
        if limit > start {
            (output + start).update(repeating: 0, count: limit - start)
        }
    }
    isSilence.pointee = ObjCBool(copied == 0)
}

// MARK: - CoreAudio 设备查询

/// 通过 CoreAudio HAL 查询输出设备。
enum AudioDevices {
    /// 构造属性地址。
    static func propertyAddress(_ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        return AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    /// 列出所有音频设备。
    static func allDevices() -> [AudioObjectID] {
        let system = AudioObjectID(kAudioObjectSystemObject)
        var address = propertyAddress(kAudioHardwarePropertyDevices)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(system, &address, 0, nil, &size) == 0, size > 0 else {
            return []
        }
        let capacity = Int(size) / MemoryLayout<AudioObjectID>.size
        guard capacity > 0 else {
            return []
        }
        var devices = [AudioObjectID](repeating: 0, count: capacity)
        let status = devices.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) -> OSStatus in
            guard let base = raw.baseAddress else {
                return -1
            }
            return AudioObjectGetPropertyData(system, &address, 0, nil, &size, base)
        }
        guard status == 0 else {
            return []
        }
        return Array(devices.prefix(Int(size) / MemoryLayout<AudioObjectID>.size))
    }

    /// 读取设备名称。
    static func name(of device: AudioObjectID) -> String? {
        var address = propertyAddress(kAudioObjectPropertyName)
        var name: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        let status = withUnsafeMutablePointer(to: &name) { pointer -> OSStatus in
            return AudioObjectGetPropertyData(device, &address, 0, nil, &size, pointer)
        }
        guard status == 0, let value = name else {
            return nil
        }
        // kAudioObjectPropertyName 返回的 CFString 需要调用方释放。
        return value.takeRetainedValue() as String
    }

    /// 读取 UInt32 类型的属性。
    static func uint32Property(_ device: AudioObjectID, _ selector: AudioObjectPropertySelector, scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> UInt32? {
        var address = propertyAddress(selector, scope)
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        let status = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value)
        return status == 0 ? value : nil
    }

    /// 判断设备是否有输出流。
    static func hasOutputStreams(_ device: AudioObjectID) -> Bool {
        var address = propertyAddress(kAudioDevicePropertyStreams, kAudioObjectPropertyScopeOutput)
        var size: UInt32 = 0
        let status = AudioObjectGetPropertyDataSize(device, &address, 0, nil, &size)
        return status == 0 && size > 0
    }

    /// 按名称查找输出设备：先精确匹配，再忽略大小写匹配。
    static func findOutputDevice(named target: String) -> AudioObjectID? {
        var candidates: [(id: AudioObjectID, name: String)] = []
        for device in allDevices() where hasOutputStreams(device) {
            if let deviceName = name(of: device) {
                candidates.append((id: device, name: deviceName))
            }
        }
        if let exact = candidates.first(where: { $0.name == target }) {
            return exact.id
        }
        if let loose = candidates.first(where: { $0.name.caseInsensitiveCompare(target) == .orderedSame }) {
            return loose.id
        }
        return nil
    }

    /// 把四字符代码转换为 UInt32（如 "hdpn"）。
    static func fourCharCode(_ text: String) -> UInt32 {
        var result: UInt32 = 0
        for byte in text.utf8.prefix(4) {
            result = (result << 8) | UInt32(byte)
        }
        return result
    }

    /// 推测设备是否为耳机：蓝牙设备，或数据源为“耳机”的内建输出。
    static func isLikelyHeadphones(_ device: AudioObjectID) -> Bool {
        let transport = uint32Property(device, kAudioDevicePropertyTransportType) ?? 0
        if transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE {
            return true
        }
        if transport == kAudioDeviceTransportTypeBuiltIn,
           let source = uint32Property(device, kAudioDevicePropertyDataSource, scope: kAudioObjectPropertyScopeOutput) {
            return source == fourCharCode("hdpn")
        }
        return false
    }

    /// 设备 IO 缓冲区大小（帧）。
    static func bufferFrameSize(_ device: AudioObjectID) -> UInt32 {
        return uint32Property(device, kAudioDevicePropertyBufferFrameSize) ?? 0
    }
}

// MARK: - Apple 空间音频渲染器

/// 声床的 CoreAudio 声道布局标签；不支持的组合返回 nil。
func bedChannelLayoutTag(channels: Int, layout: UInt16) -> AudioChannelLayoutTag? {
    switch layout {
    case Saab.layoutHoaAcnSn3d:
        // 一到三阶 Ambisonics：4、9、16 声道（Apple 最高支持三阶）。
        guard channels == 4 || channels == 9 || channels == 16 else {
            return nil
        }
        return kAudioChannelLayoutTag_HOA_ACN_SN3D | AudioChannelLayoutTag(channels)
    case Saab.layoutWave71:
        guard channels == 8 else {
            return nil
        }
        return kAudioChannelLayoutTag_WAVE_7_1
    default:
        return nil
    }
}

/// 构造带声道布局的声床格式（标准格式：非交错 float32）。
func makeBedFormat(sampleRate: Double, channels: Int, layout: UInt16) -> AVAudioFormat? {
    guard let tag = bedChannelLayoutTag(channels: channels, layout: layout),
          let channelLayout = AVAudioChannelLayout(layoutTag: tag) else {
        return nil
    }
    let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channelLayout: channelLayout)
    guard format.channelCount == AVAudioChannelCount(channels) else {
        return nil
    }
    return format
}

/// 用单位四元数旋转向量（与 Java 端 Quat.rotate 相同的展开式）。
func rotateVector(_ q: (x: Float, y: Float, z: Float, w: Float), _ v: (x: Float, y: Float, z: Float)) -> (x: Float, y: Float, z: Float) {
    let tx = 2 * (q.y * v.z - q.z * v.y)
    let ty = 2 * (q.z * v.x - q.x * v.z)
    let tz = 2 * (q.x * v.y - q.y * v.x)
    return (
        v.x + q.w * tx + (q.y * tz - q.z * ty),
        v.y + q.w * ty + (q.z * tx - q.x * tz),
        v.z + q.w * tz + (q.x * ty - q.y * tx)
    )
}

/// 听者朝向的常用取值。
enum ListenerOrientations {
    /// 听者默认朝向：看向 −Z，头顶朝 +Y。
    static var identity: AVAudio3DVectorOrientation {
        return AVAudio3DVectorOrientation(
            forward: AVAudio3DVector(x: 0, y: 0, z: -1),
            up: AVAudio3DVector(x: 0, y: 1, z: 0)
        )
    }
}

/// 把模组头部坐标系（x 右、y 前、z 上）中的头部旋转映射为 AVAudio 听者朝向（x 右、y 上、z 向后）。
/// f = q·(0,1,0)，u = q·(0,0,1)；forward = (f.x, f.z, −f.y)，up = (u.x, u.z, −u.y)。
func listenerOrientation(x: Float, y: Float, z: Float, w: Float) -> AVAudio3DVectorOrientation {
    let length = (x * x + y * y + z * z + w * w).squareRoot()
    guard length.isFinite, length > 1e-6 else {
        return ListenerOrientations.identity
    }
    let q = (x: x / length, y: y / length, z: z / length, w: w / length)
    let f = rotateVector(q, (0, 1, 0))
    let u = rotateVector(q, (0, 0, 1))
    return AVAudio3DVectorOrientation(
        forward: AVAudio3DVector(x: f.x, y: f.z, z: -f.y),
        up: AVAudio3DVector(x: u.x, y: u.z, z: -u.y)
    )
}

/// 开关环境节点的 Apple 自动头部追踪（isListenerHeadTrackingEnabled，macOS 15+），成功返回 true。
/// 该属性只存在于 macOS 15 及以上的 SDK 中，而 #available 只是运行时检查：模组在没有预编译助手时会用用户本机的
/// 工具链编译本文件，旧工具链（Xcode 15 / macOS 14 SDK）找不到该符号会导致整个助手（包括 SAHT 头部追踪）编译失败。
/// 因此 Swift 6 编译器（随 Xcode 16 与 macOS 15 SDK 一起发布）直接调用该属性，更旧的编译器改用键值编码设置同一属性。
@available(macOS 15.0, *)
func applyListenerHeadTracking(_ environment: AVAudioEnvironmentNode, enabled: Bool) -> Bool {
#if compiler(>=6.0)
    environment.isListenerHeadTrackingEnabled = enabled
    return true
#else
    // 键值编码遇到不存在的键会抛出无法捕获的 Objective-C 异常，因此先确认 setter 存在。
    guard environment.responds(to: NSSelectorFromString("setListenerHeadTrackingEnabled:")) else {
        return false
    }
    environment.setValue(enabled, forKey: "listenerHeadTrackingEnabled")
    return true
#endif
}

/// 预检声床格式能否接入环境节点。
/// AVAudioEngine.connect 遇到不支持的格式会抛出 Objective-C 异常（纯 Swift 无法捕获，进程会直接崩溃），
/// 因此先通过可抛出 Swift 错误的 AUAudioUnitBus.setFormat 检查。
/// Apple 规定不得直接修改引擎所维护节点的流格式与声道布局（见 AVAudioNode.auAudioUnit 的说明），
/// 所以预检在一个从未接入任何引擎的临时环境节点上进行，引擎中的节点完全不受影响；必须在 attach/connect 之前调用。
/// 无法预检（临时节点没有输入总线）时返回 nil，由调用方直接尝试连接。
func precheckBedFormat(_ format: AVAudioFormat, outputFormat: AVAudioFormat) -> String? {
    let probe = AVAudioEnvironmentNode()
    let unit = probe.auAudioUnit
    // 先让临时节点的输出格式与实际输出一致（同一采样率），避免因输入输出采样率不同而误报；失败时忽略。
    if unit.outputBusses.count > 0 {
        _ = try? unit.outputBusses[0].setFormat(outputFormat)
    }
    let busses = unit.inputBusses
    guard busses.count > 0 else {
        return nil
    }
    do {
        try busses[0].setFormat(format)
        return nil
    } catch {
        return "环境节点不接受该声床格式（\(format.channelCount) 声道）：\(error.localizedDescription)"
    }
}

/// 渲染器配置（由 CONFIG 得到）。
struct RendererSettings: Equatable {
    /// 声床采样率（Hz）。
    var sampleRate: Double
    /// 声床声道数。
    var channels: Int
    /// 声床布局（见 Saab.layout*）。
    var layout: UInt16
    /// 输出设备名（空表示系统默认）。
    var deviceName: String
    /// 是否优先使用 Apple 自动头部追踪。
    var preferAutoHeadTracking: Bool

    /// 判断与另一份配置相比是否需要重建音频图（头部追踪偏好变化不需要重建）。
    func needsRebuild(comparedTo other: RendererSettings) -> Bool {
        return sampleRate != other.sampleRate
            || channels != other.channels
            || layout != other.layout
            || deviceName != other.deviceName
    }
}

/// 渲染器状态快照（用于 STATUS）。
struct RendererSnapshot {
    /// 引擎是否已启动（实时状态另由 isRunning 读取）。
    var started = false
    /// Apple 自动头部追踪是否已启用。
    var autoHeadTrackingActive = false
    /// 输出设备是否推测为耳机。
    var headphones = false
    /// 声床是否已按 ambienceBed 接入且 .auto 可用。
    var bedAccepted = false
    /// 是否出错。
    var engineError = false
    /// 正在使用的渲染算法原始值。
    var renderingAlgorithm: UInt32 = 0
    /// 输出硬件采样率（Hz）。
    var hardwareSampleRate: UInt32 = 0
    /// 输出延迟（秒）。
    var outputLatency: Float = 0
    /// 输出设备名。
    var deviceName = ""
    /// 最近一次错误或警告信息。
    var lastError = ""
}

/// 使用 AVAudioEngine 与 AVAudioEnvironmentNode 把多声道声床交给 Apple 空间音频渲染。
/// 音频图的创建、重建与销毁只在主队列上进行；头部姿态与状态查询可在任意线程调用。
final class AppleSpatialRenderer: @unchecked Sendable {
    /// 声床源节点的位置。环境节点按源节点的方向旋转整个声床，(0,0,−1) 表示声床正前方与听者默认正前方（−Z）一致。
    /// 该约定未经 Apple 文档确认，--self-test 会同时尝试 (0,0,0) 与 (0,0,−1) 并报告哪一个通过。
    static let bedSourcePosition = AVAudio3DPoint(x: 0, y: 0, z: -1)

    /// 把声床源节点设置为 ambienceBed + .auto（必须在连接到环境节点之后调用）。
    static func configureBedSource(_ source: AVAudioSourceNode, position: AVAudio3DPoint) {
        // 默认的 .spatializeIfMono 会直接旁路多声道输入，默认的等功率声像也不能渲染声床，两者都必须显式设置。
        source.sourceMode = .ambienceBed
        source.renderingAlgorithm = .auto
        source.reverbBlend = 0
        source.position = position
    }

    /// 音频数据来源（进程内唯一、永不释放）。
    private let ring: AudioRing
    /// HELLO 中上报的能力位。
    private let capabilities: UInt32
    /// 状态变化回调（在主队列调用，启动前设置一次）。
    var onStateChange: (@Sendable () -> Void)?
    /// 保护下方跨线程字段的锁。
    private let lock = NSLock()
    /// 当前音频引擎（受 lock 保护）。
    private var engine: AVAudioEngine?
    /// 当前环境节点（受 lock 保护）。
    private var environment: AVAudioEnvironmentNode?
    /// 最近一次 HEAD 换算出的听者朝向（受 lock 保护）。
    private var headOrientation = ListenerOrientations.identity
    /// Apple 自动头部追踪是否已启用（受 lock 保护）。
    private var autoHeadTrackingActive = false
    /// 状态快照（受 lock 保护）。
    private var snapshotState = RendererSnapshot()
    /// 当前声床源节点（仅主队列访问）。
    private var sourceNode: AVAudioSourceNode?
    /// 引擎配置变化通知的观察者（仅主队列访问）。
    private var configurationObserver: NSObjectProtocol?
    /// 期望的配置；nil 表示空闲（仅主队列访问）。
    private var desiredSettings: RendererSettings?
    /// 构建序号，用于丢弃过期的重试（仅主队列访问）。
    private var buildSerial: UInt64 = 0
    /// 是否已安排因配置变化而重建（仅主队列访问）。
    private var rebuildScheduled = false
    /// 最近一次构建的系统运行时间（仅主队列访问）。
    private var lastBuildUptime: TimeInterval = 0

    /// 创建渲染器。
    init(ring: AudioRing, capabilities: UInt32) {
        self.ring = ring
        self.capabilities = capabilities
    }

    /// 应用新的配置；nil 表示空闲（停止引擎）。可在任意线程调用。
    func apply(_ settings: RendererSettings?, configError: String?) {
        DispatchQueue.main.async {
            self.applyOnMain(settings, configError: configError)
        }
    }

    /// 更新头部姿态（来自 HEAD）。Apple 自动头部追踪启用时忽略。可在任意线程调用。
    func setHeadPose(_ pose: BridgeHeadPose) {
        let orientation = pose.valid ? listenerOrientation(x: pose.x, y: pose.y, z: pose.z, w: pose.w) : ListenerOrientations.identity
        lock.lock()
        headOrientation = orientation
        // 在锁内写入听者朝向：主队列切换自动头部追踪时也在同一把锁内写入，避免这里的迟到写入留下过期朝向。
        if !autoHeadTrackingActive {
            environment?.listenerVectorOrientation = orientation
        }
        lock.unlock()
    }

    /// 读取状态快照。可在任意线程调用。
    func snapshot() -> (state: RendererSnapshot, running: Bool) {
        lock.lock()
        let state = snapshotState
        let currentEngine = engine
        lock.unlock()
        return (state, currentEngine?.isRunning ?? false)
    }

    /// 在主队列上应用配置。
    private func applyOnMain(_ settings: RendererSettings?, configError: String?) {
        let previous = desiredSettings
        desiredSettings = settings
        guard let settings = settings else {
            teardown()
            lock.lock()
            snapshotState = RendererSnapshot()
            snapshotState.engineError = configError != nil
            snapshotState.lastError = configError ?? ""
            lock.unlock()
            notifyStateChange()
            return
        }
        if currentEngine() != nil, let previous = previous, !settings.needsRebuild(comparedTo: previous) {
            if previous.preferAutoHeadTracking != settings.preferAutoHeadTracking {
                updateHeadTrackingMode()
            }
            notifyStateChange()
            return
        }
        rebuild()
    }

    /// 读取当前引擎。
    private func currentEngine() -> AVAudioEngine? {
        lock.lock()
        let current = engine
        lock.unlock()
        return current
    }

    /// 通知状态变化。
    private func notifyStateChange() {
        onStateChange?()
    }

    /// 销毁当前音频图并按期望配置重建；失败时 2 秒后重试。
    private func rebuild() {
        teardown()
        buildSerial &+= 1
        guard let settings = desiredSettings else {
            notifyStateChange()
            return
        }
        lastBuildUptime = ProcessInfo.processInfo.systemUptime
        let serial = buildSerial
        if !build(settings) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                guard let self = self, self.buildSerial == serial, self.desiredSettings != nil, self.currentEngine() == nil else {
                    return
                }
                self.rebuild()
            }
        }
        notifyStateChange()
    }

    /// 记录构建失败。
    private func recordFailure(_ message: String) {
        logMessage("Apple 空间音频渲染器：" + message)
        lock.lock()
        snapshotState = RendererSnapshot()
        snapshotState.engineError = true
        snapshotState.lastError = message
        lock.unlock()
    }

    /// 按配置创建并启动音频图，成功返回 true。
    private func build(_ settings: RendererSettings) -> Bool {
        var warnings: [String] = []
        let newEngine = AVAudioEngine()

        // 1. 输出设备：必须在访问主混音器、建立连接之前设置。
        if !settings.deviceName.isEmpty {
            if let deviceID = AudioDevices.findOutputDevice(named: settings.deviceName) {
                do {
                    try newEngine.outputNode.auAudioUnit.setDeviceID(deviceID)
                } catch {
                    warnings.append("无法切换到输出设备“\(settings.deviceName)”，已使用系统默认输出设备：\(error.localizedDescription)")
                }
            } else {
                warnings.append("未找到输出设备“\(settings.deviceName)”，已使用系统默认输出设备。")
            }
        }

        // 2. 格式：声床格式必须携带声道布局，环境节点据此确定每个声道的方向。
        guard let bedFormat = makeBedFormat(sampleRate: settings.sampleRate, channels: settings.channels, layout: settings.layout) else {
            recordFailure("不支持的声床格式：\(settings.channels) 声道，布局 \(settings.layout)。")
            return false
        }
        guard let stereoFormat = AVAudioFormat(standardFormatWithSampleRate: settings.sampleRate, channels: 2) else {
            recordFailure("无法创建立体声输出格式。")
            return false
        }
        // 在临时节点上预检声床格式（不触碰引擎中的节点），避免 connect 因格式不受支持而抛出无法捕获的异常。
        if let problem = precheckBedFormat(bedFormat, outputFormat: stereoFormat) {
            recordFailure(problem)
            return false
        }

        // 3. 节点：实时渲染闭包只捕获环形缓冲区句柄（两个裸指针，所指内存在进程生命周期内永不释放）。
        let ringHandle = ring.handle
        let source = AVAudioSourceNode(format: bedFormat) { isSilence, _, frameCount, outputData -> OSStatus in
            renderFromRing(ringHandle, frameCount: Int(frameCount), outputData: outputData, isSilence: isSilence)
            return 0
        }
        let newEnvironment = AVAudioEnvironmentNode()
        newEngine.attach(newEnvironment)
        newEngine.attach(source)
        newEngine.connect(newEnvironment, to: newEngine.mainMixerNode, format: stereoFormat)
        let bus = newEnvironment.nextAvailableInputBus
        newEngine.connect(source, to: newEnvironment, fromBus: 0, toBus: bus, format: bedFormat)
        AppleSpatialRenderer.configureBedSource(source, position: AppleSpatialRenderer.bedSourcePosition)
        newEnvironment.outputType = .auto
        newEnvironment.reverbParameters.enable = false
        newEnvironment.listenerPosition = AVAudio3DPoint(x: 0, y: 0, z: 0)
        newEnvironment.listenerVectorOrientation = ListenerOrientations.identity
        let applicable = newEnvironment.applicableRenderingAlgorithms.map { $0.intValue }
        let bedAccepted = applicable.contains(AVAudio3DMixingRenderingAlgorithm.auto.rawValue)
        if !bedAccepted {
            warnings.append("当前输出格式不支持 .auto 渲染算法（可用：\(applicable)），空间化效果可能不完整。")
        }

        // 4. 监听设备或格式变化：在主队列上重建，绝不在通知回调里释放引擎。
        let observer = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: newEngine, queue: nil) { [weak self] _ in
            guard let renderer = self else {
                return
            }
            DispatchQueue.main.async {
                renderer.scheduleRebuildAfterConfigurationChange()
            }
        }

        // 5. 启动。新音频图的 IO 拉取大小可能与旧的不同（设备或缓冲区大小变化），先清零统计，由渲染线程重新记录。
        ring.resetPullStatistics()
        newEngine.prepare()
        do {
            try newEngine.start()
        } catch {
            NotificationCenter.default.removeObserver(observer)
            newEngine.stop()
            recordFailure("音频引擎启动失败：\(error.localizedDescription)")
            return false
        }

        configurationObserver = observer
        sourceNode = source
        lock.lock()
        engine = newEngine
        environment = newEnvironment
        lock.unlock()
        updateHeadTrackingMode()

        // 6. 记录输出设备信息供 STATUS 使用。
        let outputNode = newEngine.outputNode
        let deviceID = outputNode.auAudioUnit.deviceID
        let hardwareRate = outputNode.outputFormat(forBus: 0).sampleRate
        let bufferFrames = AudioDevices.bufferFrameSize(deviceID)
        var latency = outputNode.presentationLatency
        if hardwareRate > 0 && bufferFrames > 0 {
            latency += Double(bufferFrames) / hardwareRate
        }
        let deviceName = AudioDevices.name(of: deviceID) ?? ""
        let headphones = AudioDevices.isLikelyHeadphones(deviceID)
        let algorithm = UInt32(clamping: source.renderingAlgorithm.rawValue)
        lock.lock()
        let automatic = autoHeadTrackingActive
        snapshotState = RendererSnapshot(
            started: true,
            autoHeadTrackingActive: automatic,
            headphones: headphones,
            bedAccepted: bedAccepted,
            engineError: false,
            renderingAlgorithm: algorithm,
            hardwareSampleRate: hardwareRate.isFinite && hardwareRate > 0 ? UInt32(hardwareRate.rounded()) : 0,
            outputLatency: latency.isFinite ? Float(latency) : 0,
            deviceName: deviceName,
            lastError: warnings.joined(separator: " ")
        )
        lock.unlock()
        for warning in warnings {
            logMessage("Apple 空间音频渲染器：" + warning)
        }
        logMessage("Apple 空间音频渲染器已启动：\(settings.channels) 声道，输出设备“\(deviceName)”，硬件采样率 \(Int(hardwareRate)) Hz，渲染算法 \(algorithm)")
        return true
    }

    /// 停止并释放当前音频图。
    private func teardown() {
        if let observer = configurationObserver {
            NotificationCenter.default.removeObserver(observer)
            configurationObserver = nil
        }
        lock.lock()
        let oldEngine = engine
        engine = nil
        environment = nil
        autoHeadTrackingActive = false
        snapshotState.started = false
        snapshotState.autoHeadTrackingActive = false
        lock.unlock()
        // stop() 是同步的：返回后渲染闭包不会再被调用。
        oldEngine?.stop()
        sourceNode = nil
    }

    /// 根据配置与能力切换 Apple 自动头部追踪或模组 HEAD 姿态。
    /// 开关、状态与听者朝向在同一把锁内一起更新，读取线程的 setHeadPose 不会与之交错。
    private func updateHeadTrackingMode() {
        let entitled = capabilities & Saab.capabilityAutoHeadTracking != 0
        let wantAutomatic = entitled && (desiredSettings?.preferAutoHeadTracking ?? false)
        lock.lock()
        defer {
            lock.unlock()
        }
        guard let target = environment else {
            return
        }
        var automatic = false
        if entitled {
            if #available(macOS 15.0, *) {
                automatic = applyListenerHeadTracking(target, enabled: wantAutomatic) && wantAutomatic
            }
        }
        autoHeadTrackingActive = automatic
        snapshotState.autoHeadTrackingActive = automatic
        target.listenerVectorOrientation = automatic ? ListenerOrientations.identity : headOrientation
    }

    /// 音频设备或格式变化后安排重建（合并短时间内的多次通知，且两次构建至少间隔 1 秒）。
    private func scheduleRebuildAfterConfigurationChange() {
        guard !rebuildScheduled, desiredSettings != nil else {
            return
        }
        rebuildScheduled = true
        let sinceBuild = ProcessInfo.processInfo.systemUptime - lastBuildUptime
        let delay = max(0.25, 1.0 - sinceBuild)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self else {
                return
            }
            self.rebuildScheduled = false
            guard self.desiredSettings != nil else {
                return
            }
            logMessage("音频设备配置已变化，正在重建 Apple 空间音频渲染器")
            self.rebuild()
        }
    }
}

// MARK: - SAAB 音频桥客户端

/// 连接模组的 SAAB 服务器：发送 HELLO、接收 CONFIG/AUDIO/HEAD、按需发送 REQUEST 与 STATUS。
final class AudioBridgeClient: @unchecked Sendable {
    /// 服务器端口（127.0.0.1）。
    private let port: UInt16
    /// 握手令牌（ASCII 字节）。
    private let token: [UInt8]
    /// 能力位。
    private let capabilities: UInt32
    /// 环形缓冲区。
    private let ring: AudioRing
    /// Apple 空间音频渲染器。
    private let renderer: AppleSpatialRenderer
    /// 保护连接状态与请求计数的锁。
    private let stateLock = NSLock()
    /// 串行化套接字写入的锁（关闭套接字也在此锁内进行，避免描述符被复用）。
    private let writeLock = NSLock()
    /// 发送状态消息的串行队列。
    private let statusQueue = DispatchQueue(label: "SpatialAudio.AudioBridge.Status")
    /// 状态定时器。
    private var statusTimer: DispatchSourceTimer?
    /// 当前套接字（-1 表示未连接，受 stateLock 保护）。
    private var socketFd: Int32 = -1
    /// 当前配置代次（受 stateLock 保护）。
    private var generation: UInt32 = 0
    /// 当前声道数，0 表示空闲（受 stateLock 保护）。
    private var activeChannels = 0
    /// 目标缓冲帧数（受 stateLock 保护）。
    private var targetFrames = 0
    /// 已请求但尚未收到的帧数（受 stateLock 保护）。
    private var outstanding = 0
    /// 最近一次收到音频或开始等待的系统运行时间（受 stateLock 保护）。
    private var lastProgressUptime: TimeInterval = 0
    /// 接收负载的缓冲区（仅读取线程使用，大小为协议上限）。
    private let payloadBuffer: UnsafeMutableRawPointer
    /// 接收帧头的缓冲区（仅读取线程使用）。
    private let headerBuffer: UnsafeMutableRawPointer

    /// 创建客户端。
    init(port: UInt16, token: [UInt8], capabilities: UInt32, ring: AudioRing, renderer: AppleSpatialRenderer) {
        self.port = port
        self.token = token
        self.capabilities = capabilities
        self.ring = ring
        self.renderer = renderer
        payloadBuffer = UnsafeMutableRawPointer.allocate(byteCount: Saab.maxPayload, alignment: 16)
        headerBuffer = UnsafeMutableRawPointer.allocate(byteCount: Saab.headerSize, alignment: 16)
    }

    deinit {
        payloadBuffer.deallocate()
        headerBuffer.deallocate()
    }

    /// 启动连接线程、请求线程与状态定时器。
    func start() {
        renderer.onStateChange = { [weak self] in
            self?.requestStatusSoon()
        }
        let connectionThread = Thread { [weak self] in
            self?.connectionLoop()
        }
        connectionThread.name = "SpatialAudio.AudioBridge.Reader"
        connectionThread.qualityOfService = .userInteractive
        connectionThread.start()

        let creditThread = Thread { [weak self] in
            self?.creditLoop()
        }
        creditThread.name = "SpatialAudio.AudioBridge.Credit"
        creditThread.qualityOfService = .userInteractive
        creditThread.start()

        let timer = DispatchSource.makeTimerSource(queue: statusQueue)
        timer.schedule(deadline: .now() + .milliseconds(Saab.statusIntervalMilliseconds), repeating: .milliseconds(Saab.statusIntervalMilliseconds))
        timer.setEventHandler { [weak self] in
            self?.sendStatus()
        }
        timer.resume()
        statusTimer = timer
        logMessage("音频桥已启动：目标端口 \(port)，能力位 \(capabilities)")
    }

    /// 连接循环：连接失败或断开后每秒重试。
    private func connectionLoop() {
        var reportedFailure = false
        var quiet = false
        // 连续被模组很快关闭的连接次数：令牌已失效（本实例已被新启动的助手替换）时会一直如此。
        var rejectedInARow = 0
        while true {
            if let fd = openConnection() {
                reportedFailure = false
                if !quiet {
                    logMessage("已连接到模组音频桥（端口 \(port)）")
                }
                let connectedAt = ProcessInfo.processInfo.systemUptime
                let hello = SaabCodec.helloPayload(
                    token: token,
                    capabilities: capabilities,
                    macOSMajor: BridgeCapabilities.macOSMajorVersion(),
                    pid: UInt32(bitPattern: getpid())
                )
                // HELLO 必须是第一帧：在把套接字交给其他线程（状态定时器、请求线程）之前直接写出。
                if writeAll(fd, SaabCodec.frame(type: Saab.typeHello, payload: hello)) {
                    install(fd)
                    sendStatus()
                    readLoop(fd)
                }
                closeConnection(fd)
                // 连接很快就被关闭（例如令牌被拒绝）时，后续的重复日志不再输出。
                let shortLived = ProcessInfo.processInfo.systemUptime - connectedAt < 2.0
                if !quiet {
                    logMessage("模组音频桥连接已断开，将在 1 秒后重试")
                }
                quiet = shortLived
                rejectedInARow = shortLived ? rejectedInARow + 1 : 0
                if rejectedInARow >= Saab.maxRejectedConnections {
                    // 模组每次重启助手都会更换令牌：持续被拒绝说明本实例已经过时，退出以免与新实例争抢。
                    logMessage("连续 \(rejectedInARow) 次被模组拒绝，本助手实例已过时，退出")
                    exit(0)
                }
            } else if !reportedFailure {
                reportedFailure = true
                quiet = false
                logMessage("无法连接模组音频桥（端口 \(port)），将每秒重试")
            }
            Thread.sleep(forTimeInterval: Saab.reconnectSeconds)
        }
    }

    /// 建立 TCP 连接，失败返回 nil。
    private func openConnection() -> Int32? {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else {
            return nil
        }
        var one: Int32 = 1
        _ = setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        // 对端关闭后写入不产生 SIGPIPE，而是返回 EPIPE。
        _ = setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        // 写入最多阻塞 2 秒，防止对端卡死时永久阻塞。
        var timeout = timeval(tv_sec: 2, tv_usec: 0)
        _ = setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let result = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
                Darwin.connect(fd, socketAddress, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard result == 0 else {
            Darwin.close(fd)
            return nil
        }
        return fd
    }

    /// 记录新连接并重置状态（新连接在收到 CONFIG 之前保持空闲）。
    private func install(_ fd: Int32) {
        ring.reset(channels: 0, startThreshold: 1)
        stateLock.lock()
        socketFd = fd
        generation = 0
        activeChannels = 0
        targetFrames = 0
        outstanding = 0
        lastProgressUptime = ProcessInfo.processInfo.systemUptime
        stateLock.unlock()
    }

    /// 关闭连接并进入空闲。
    private func closeConnection(_ fd: Int32) {
        // 先 shutdown 让可能阻塞在 send 中的线程立即返回，再在写锁内关闭描述符。
        _ = Darwin.shutdown(fd, SHUT_RDWR)
        writeLock.lock()
        stateLock.lock()
        socketFd = -1
        generation = 0
        activeChannels = 0
        targetFrames = 0
        outstanding = 0
        stateLock.unlock()
        _ = Darwin.close(fd)
        writeLock.unlock()
        ring.reset(channels: 0, startThreshold: 1)
        renderer.apply(nil, configError: nil)
    }

    /// 从套接字读满指定字节数；连接关闭或出错时返回 false。
    private func readFully(_ fd: Int32, _ buffer: UnsafeMutableRawPointer, _ count: Int) -> Bool {
        var done = 0
        while done < count {
            let received = Darwin.recv(fd, buffer + done, count - done, 0)
            if received > 0 {
                done += received
                continue
            }
            if received < 0 && errno == EINTR {
                continue
            }
            return false
        }
        return true
    }

    /// 读取并处理消息，直到连接断开或出现协议错误。
    private func readLoop(_ fd: Int32) {
        while true {
            guard readFully(fd, headerBuffer, Saab.headerSize) else {
                return
            }
            guard let header = SaabCodec.decodeHeader(UnsafeRawBufferPointer(start: headerBuffer, count: Saab.headerSize)) else {
                logMessage("音频桥协议错误：帧头无效或负载过大，断开连接")
                return
            }
            if header.length > 0 {
                guard readFully(fd, payloadBuffer, header.length) else {
                    return
                }
            }
            let payload = UnsafeRawBufferPointer(start: payloadBuffer, count: header.length)
            guard handleMessage(type: header.type, payload: payload) else {
                return
            }
        }
    }

    /// 处理一条消息；出现协议错误时返回 false（调用方断开连接）。
    private func handleMessage(type: UInt16, payload: UnsafeRawBufferPointer) -> Bool {
        switch type {
        case Saab.typeConfig:
            guard let config = SaabCodec.decodeConfig(payload) else {
                logMessage("音频桥协议错误：CONFIG 长度无效（\(payload.count) 字节）")
                return false
            }
            applyConfig(config)
            return true
        case Saab.typeAudio:
            return handleAudio(payload)
        case Saab.typeHead:
            guard let pose = SaabCodec.decodeHead(payload) else {
                logMessage("音频桥协议错误：HEAD 长度无效（\(payload.count) 字节）")
                return false
            }
            renderer.setHeadPose(pose)
            return true
        default:
            // 未知或不应由模组发送的消息：负载已读完，直接忽略，便于协议扩展。
            return true
        }
    }

    /// 应用 CONFIG：校验格式、重置缓冲区与请求计数、通知渲染器。
    private func applyConfig(_ config: BridgeConfig) {
        var channels = Int(config.channels)
        var settings: RendererSettings?
        var problem: String?
        if channels > 0 {
            if bedChannelLayoutTag(channels: channels, layout: config.layout) == nil {
                problem = "不支持的声床格式：\(channels) 声道，布局 \(config.layout)。"
            } else if config.sampleRate < 8000 || config.sampleRate > 384_000 {
                problem = "不支持的采样率：\(config.sampleRate) Hz。"
            } else {
                settings = RendererSettings(
                    sampleRate: Double(config.sampleRate),
                    channels: channels,
                    layout: config.layout,
                    deviceName: config.deviceName,
                    preferAutoHeadTracking: config.flags & Saab.configPreferAutoHeadTracking != 0
                )
            }
            if problem != nil {
                channels = 0
            }
        }
        let target = channels > 0
            ? min(max(Int(config.targetFrames), Saab.minimumTargetFrames), AudioRing.capacityFrames / 4)
            : 0
        ring.reset(channels: channels, startThreshold: max(1, target / 2))
        stateLock.lock()
        generation = config.generation
        activeChannels = channels
        targetFrames = target
        outstanding = 0
        lastProgressUptime = ProcessInfo.processInfo.systemUptime
        stateLock.unlock()
        if let problem = problem {
            logMessage("音频桥：" + problem)
        }
        renderer.apply(settings, configError: problem)
        requestStatusSoon()
    }

    /// 处理 AUDIO：当前代次写入环形缓冲区，过期代次丢弃。
    private func handleAudio(_ payload: UnsafeRawBufferPointer) -> Bool {
        var reader = ByteReader(payload)
        guard let audioGeneration = reader.u32(), let frameCount = reader.u32() else {
            logMessage("音频桥协议错误：AUDIO 长度无效（\(payload.count) 字节）")
            return false
        }
        stateLock.lock()
        let currentGeneration = generation
        let channels = activeChannels
        stateLock.unlock()
        guard audioGeneration == currentGeneration, channels > 0 else {
            // 过期代次或空闲状态：丢弃。
            return true
        }
        let frames = Int(frameCount)
        guard frames <= Saab.maxPayload, payload.count == 8 + frames * channels * MemoryLayout<Float>.size else {
            logMessage("音频桥协议错误：AUDIO 负载长度 \(payload.count) 与 \(frames) 帧 × \(channels) 声道不符")
            return false
        }
        if frames > 0, let base = payload.baseAddress {
            ring.writeInterleaved(base + 8, frames: frames, channels: channels)
        }
        stateLock.lock()
        if generation == audioGeneration {
            outstanding = max(0, outstanding - frames)
            lastProgressUptime = ProcessInfo.processInfo.systemUptime
        }
        stateLock.unlock()
        return true
    }

    /// 请求循环：保持“缓冲帧数 + 未完成请求”约等于目标值。
    private func creditLoop() {
        while true {
            usleep(Saab.creditLoopMicroseconds)
            stateLock.lock()
            guard socketFd >= 0, activeChannels > 0, targetFrames > 0 else {
                stateLock.unlock()
                continue
            }
            let now = ProcessInfo.processInfo.systemUptime
            if outstanding > 0 && now - lastProgressUptime > Saab.staleCreditSeconds {
                // 已请求的数据长时间未到达（模组可能已丢弃请求），重新计算，避免永久停滞。
                outstanding = 0
                lastProgressUptime = now
            }
            let buffered = ring.bufferedFrames()
            // CONFIG 的目标可能小于音频 IO 线程单次拉取的帧数（例如 10 毫秒 = 480 帧，而 IO 周期为 512 帧以上），
            // 那样缓冲永远凑不满一次拉取、每个周期都会欠载；因此目标至少取两倍拉取帧数再加一次最小请求的余量。
            let effectiveTarget = min(max(targetFrames, 2 * ring.maxPullFrames() + Saab.minimumRequestFrames), AudioRing.capacityFrames / 4)
            let want = effectiveTarget - buffered - outstanding
            guard want >= Saab.minimumRequestFrames else {
                stateLock.unlock()
                continue
            }
            if outstanding == 0 {
                lastProgressUptime = now
            }
            outstanding += want
            let requestGeneration = generation
            stateLock.unlock()
            sendFrame(type: Saab.typeRequest, payload: SaabCodec.requestPayload(generation: requestGeneration, frames: UInt32(want)))
        }
    }

    /// 尽快在状态队列上发送一次 STATUS。
    private func requestStatusSoon() {
        statusQueue.async { [weak self] in
            self?.sendStatus()
        }
    }

    /// 发送 STATUS（未连接时跳过）。
    private func sendStatus() {
        stateLock.lock()
        let connected = socketFd >= 0
        let currentGeneration = generation
        stateLock.unlock()
        guard connected else {
            return
        }
        let snapshot = renderer.snapshot()
        var status = BridgeStatus()
        if snapshot.running {
            status.flags |= Saab.statusEngineRunning
        }
        if snapshot.state.autoHeadTrackingActive {
            status.flags |= Saab.statusAutoHeadTrackingActive
        }
        if snapshot.state.headphones {
            status.flags |= Saab.statusHeadphones
        }
        if snapshot.state.bedAccepted {
            status.flags |= Saab.statusBedAccepted
        }
        if snapshot.state.engineError {
            status.flags |= Saab.statusEngineError
        }
        status.generation = currentGeneration
        status.bufferedFrames = UInt32(clamping: ring.bufferedFrames())
        status.underruns = ring.underrunCount()
        status.renderingAlgorithm = snapshot.state.renderingAlgorithm
        status.hardwareSampleRate = snapshot.state.hardwareSampleRate
        status.outputLatency = snapshot.state.outputLatency
        status.deviceName = snapshot.state.deviceName
        status.lastError = snapshot.state.lastError
        sendFrame(type: Saab.typeStatus, payload: SaabCodec.statusPayload(status))
    }

    /// 发送一帧；失败时 shutdown 套接字，让读取线程断开并重连。
    @discardableResult
    private func sendFrame(type: UInt16, payload: Data) -> Bool {
        let frame = SaabCodec.frame(type: type, payload: payload)
        writeLock.lock()
        defer {
            writeLock.unlock()
        }
        stateLock.lock()
        let fd = socketFd
        stateLock.unlock()
        guard fd >= 0 else {
            return false
        }
        let sent = writeAll(fd, frame)
        if !sent {
            _ = Darwin.shutdown(fd, SHUT_RDWR)
        }
        return sent
    }

    /// 把数据完整写入套接字（处理部分写入与 EINTR），失败返回 false。
    private func writeAll(_ fd: Int32, _ data: Data) -> Bool {
        return data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
            guard let base = raw.baseAddress else {
                return true
            }
            var done = 0
            while done < raw.count {
                let written = Darwin.send(fd, base + done, raw.count - done, 0)
                if written > 0 {
                    done += written
                    continue
                }
                if written < 0 && errno == EINTR {
                    continue
                }
                return false
            }
            return true
        }
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

// MARK: - 自检

/// 输出一行自检结果到标准输出（不经过 stdio 缓冲：即使后续检查崩溃，已完成的结果也不会丢失）。
func reportLine(_ text: String) {
    FileHandle.standardOutput.write((text + "\n").data(using: .utf8) ?? Data())
}

/// 自检用的确定性宽带噪声（线性同余发生器）。
final class NoiseGenerator {
    /// 发生器状态。
    private var state: UInt32

    /// 用种子创建发生器。
    init(seed: UInt32) {
        state = seed
    }

    /// 下一个样本，范围约为 [−0.25, 0.25]。
    func next() -> Float {
        state = state &* 1_664_525 &+ 1_013_904_223
        return Float(Int32(bitPattern: state)) / 2_147_483_648.0 * 0.25
    }
}

/// 离线渲染检查的期望结果。
enum RenderExpectation {
    /// 左声道能量明显大于右声道。
    case leftLouder
    /// 右声道能量明显大于左声道。
    case rightLouder
    /// 左右基本平衡。
    case balanced
}

/// 一个离线渲染检查用例。
struct RenderCase {
    /// 用例名称。
    var name: String
    /// 声床声道数。
    var channels: Int
    /// 声床布局。
    var layout: UInt16
    /// 每个声道的测试信号增益。
    var gains: [Float]
    /// 听者向左偏航的角度（度，经 HEAD 映射施加）。
    var yawLeftDegrees: Float
    /// 期望结果。
    var expectation: RenderExpectation
}

/// 一次离线渲染的结果。
struct RenderResult {
    /// 左声道能量。
    var left: Double = 0
    /// 右声道能量。
    var right: Double = 0
    /// 正在使用的渲染算法原始值。
    var algorithm: Int = -1
    /// 环境节点的可用渲染算法。
    var applicable: [Int] = []
    /// 错误描述（成功时为 nil）。
    var error: String?
}

/// 构造单个声道为 1、其余为 0 的增益表。
func renderGains(channels: Int, active: [Int]) -> [Float] {
    var gains = [Float](repeating: 0, count: channels)
    for index in active where index >= 0 && index < channels {
        gains[index] = 1
    }
    return gains
}

/// 用离线手动渲染模式渲染 1 秒测试声床，返回左右声道能量。不需要音频设备，也不触发权限请求。
func runRenderCheck(_ testCase: RenderCase, sourcePosition: AVAudio3DPoint) -> RenderResult {
    var result = RenderResult()
    let sampleRate = 48_000.0
    let engine = AVAudioEngine()
    guard let stereoFormat = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 2) else {
        result.error = "无法创建立体声格式"
        return result
    }
    // 必须在访问主混音器或输出节点之前进入手动渲染模式，否则引擎会去连接真实的音频设备。
    do {
        try engine.enableManualRenderingMode(.offline, format: stereoFormat, maximumFrameCount: 4096)
    } catch {
        result.error = "无法进入离线渲染模式：\(error.localizedDescription)"
        return result
    }
    guard let bedFormat = makeBedFormat(sampleRate: sampleRate, channels: testCase.channels, layout: testCase.layout) else {
        result.error = "无法创建声床格式"
        return result
    }
    // 与运行时相同：在接入引擎之前，用临时节点预检声床格式。
    if let problem = precheckBedFormat(bedFormat, outputFormat: stereoFormat) {
        result.error = problem
        return result
    }
    let generator = NoiseGenerator(seed: 0x2545_F491)
    let gains = testCase.gains
    let source = AVAudioSourceNode(format: bedFormat) { isSilence, _, frameCount, outputData -> OSStatus in
        let buffers = UnsafeMutableAudioBufferListPointer(outputData)
        let frames = Int(frameCount)
        for frame in 0..<frames {
            let sample = generator.next()
            for index in 0..<buffers.count {
                guard let data = buffers[index].mData, Int(buffers[index].mDataByteSize) >= (frame + 1) * MemoryLayout<Float>.size else {
                    continue
                }
                let gain: Float = index < gains.count ? gains[index] : 0
                data.assumingMemoryBound(to: Float.self)[frame] = sample * gain
            }
        }
        isSilence.pointee = false
        return 0
    }
    let environment = AVAudioEnvironmentNode()
    engine.attach(environment)
    engine.attach(source)
    engine.connect(environment, to: engine.mainMixerNode, format: stereoFormat)
    let bus = environment.nextAvailableInputBus
    engine.connect(source, to: environment, fromBus: 0, toBus: bus, format: bedFormat)
    AppleSpatialRenderer.configureBedSource(source, position: sourcePosition)
    environment.outputType = .headphones
    environment.reverbParameters.enable = false
    environment.listenerPosition = AVAudio3DPoint(x: 0, y: 0, z: 0)
    let halfYaw = testCase.yawLeftDegrees * Float.pi / 360
    // 绕头部坐标系 z 轴（向上）旋转，正角度为向左转头，与 HEAD 消息一致。
    environment.listenerVectorOrientation = listenerOrientation(x: 0, y: 0, z: sin(halfYaw), w: cos(halfYaw))
    result.algorithm = source.renderingAlgorithm.rawValue
    result.applicable = environment.applicableRenderingAlgorithms.map { $0.intValue }

    engine.prepare()
    do {
        try engine.start()
    } catch {
        result.error = "离线引擎启动失败：\(error.localizedDescription)"
        return result
    }
    defer {
        engine.stop()
    }
    guard let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat, frameCapacity: engine.manualRenderingMaximumFrameCount) else {
        result.error = "无法分配渲染缓冲区"
        return result
    }
    var remaining = Int(sampleRate)
    while remaining > 0 {
        let request = AVAudioFrameCount(min(remaining, Int(buffer.frameCapacity)))
        let status: AVAudioEngineManualRenderingStatus
        do {
            status = try engine.renderOffline(request, to: buffer)
        } catch {
            result.error = "离线渲染失败：\(error.localizedDescription)"
            return result
        }
        guard status == .success else {
            result.error = "离线渲染状态 \(status.rawValue)"
            return result
        }
        let frames = Int(buffer.frameLength)
        guard frames > 0, let channelData = buffer.floatChannelData, buffer.format.channelCount >= 2 else {
            result.error = "离线渲染没有输出"
            return result
        }
        let left = channelData[0]
        let right = channelData[1]
        for index in 0..<frames {
            let l = Double(left[index])
            let r = Double(right[index])
            result.left += l * l
            result.right += r * r
        }
        remaining -= frames
    }
    return result
}

/// 判断离线渲染结果是否符合期望。
func evaluateRender(_ result: RenderResult, _ expectation: RenderExpectation) -> Bool {
    guard result.error == nil, result.left.isFinite, result.right.isFinite else {
        return false
    }
    // 1 秒、幅度 0.25 的噪声能量约为 1000，远高于该静音阈值。
    let minimumEnergy = 1e-4
    switch expectation {
    case .leftLouder:
        return result.left > minimumEnergy && result.left > 1.5 * result.right
    case .rightLouder:
        return result.right > minimumEnergy && result.right > 1.5 * result.left
    case .balanced:
        guard result.left > minimumEnergy, result.right > minimumEnergy else {
            return false
        }
        let ratio = result.left / result.right
        return ratio > 0.67 && ratio < 1.5
    }
}

/// 离线渲染检查：对每个声床源位置候选依次运行全部用例，至少一个候选全部通过才算通过。
func renderSelfCheck() -> Bool {
    let cases: [RenderCase] = [
        RenderCase(name: "hoa3Left", channels: 16, layout: Saab.layoutHoaAcnSn3d, gains: renderGains(channels: 16, active: [0, 1]), yawLeftDegrees: 0, expectation: .leftLouder),
        RenderCase(name: "hoa3FrontYawLeft90", channels: 16, layout: Saab.layoutHoaAcnSn3d, gains: renderGains(channels: 16, active: [0, 3]), yawLeftDegrees: 90, expectation: .rightLouder),
        RenderCase(name: "hoa3FrontBalanced", channels: 16, layout: Saab.layoutHoaAcnSn3d, gains: renderGains(channels: 16, active: [0, 3]), yawLeftDegrees: 0, expectation: .balanced),
        RenderCase(name: "surround71SideLeft", channels: 8, layout: Saab.layoutWave71, gains: renderGains(channels: 8, active: [6]), yawLeftDegrees: 0, expectation: .leftLouder)
    ]
    let candidates: [(label: String, position: AVAudio3DPoint)] = [
        ("(0,0,0)", AVAudio3DPoint(x: 0, y: 0, z: 0)),
        ("(0,0,-1)", AVAudio3DPoint(x: 0, y: 0, z: -1))
    ]
    let inUse = AppleSpatialRenderer.bedSourcePosition
    let inUseLabel = "(\(formatCoordinate(inUse.x)),\(formatCoordinate(inUse.y)),\(formatCoordinate(inUse.z)))"
    var passing: [String] = []
    for candidate in candidates {
        var allPassed = true
        for testCase in cases {
            let result = runRenderCheck(testCase, sourcePosition: candidate.position)
            let passed = evaluateRender(result, testCase.expectation)
            allPassed = allPassed && passed
            let ratio = result.right > 0 ? result.left / result.right : Double.infinity
            var line = "renderCheck position=\(candidate.label) case=\(testCase.name)"
            line += " L=\(String(format: "%.4f", result.left)) R=\(String(format: "%.4f", result.right)) L/R=\(String(format: "%.3f", ratio))"
            line += " renderingAlgorithm=\(result.algorithm) applicableRenderingAlgorithms=\(result.applicable)"
            line += " result=\(passed ? "PASS" : "FAIL")"
            if let error = result.error {
                line += " error=\(error)"
            }
            reportLine(line)
        }
        reportLine("bedSourcePosition\(candidate.label)=\(allPassed ? "PASS" : "FAIL")")
        if allPassed {
            passing.append(candidate.label)
        }
    }
    reportLine("bedSourcePositionPassing=\(passing.isEmpty ? "none" : passing.joined(separator: ","))")
    reportLine("bedSourcePositionInUse=\(inUseLabel) inUsePasses=\(passing.contains(inUseLabel))")
    return !passing.isEmpty
}

/// 把坐标格式化为不带多余小数的文本（如 0、-1）。
func formatCoordinate(_ value: Float) -> String {
    if value == value.rounded() {
        return String(Int(value))
    }
    return String(value)
}

/// SAAB 编解码与头部映射自检。
func protocolSelfCheck() -> Bool {
    var passed = true

    // HELLO：帧头 12 字节 + 负载 48 字节。
    let token = Array("0123456789abcdef0123456789abcdef".utf8)
    let hello = SaabCodec.frame(type: Saab.typeHello, payload: SaabCodec.helloPayload(token: token, capabilities: 5, macOSMajor: 15, pid: 42))
    passed = passed && hello.count == Saab.headerSize + Saab.helloPayloadSize && Array(hello.prefix(4)) == Saab.magic
    let header = hello.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
        SaabCodec.decodeHeader(raw)
    }
    passed = passed && header?.type == Saab.typeHello && header?.length == Saab.helloPayloadSize

    // CONFIG 往返与截断检测。
    var writer = PacketWriter()
    writer.append(UInt32(7))
    writer.append(UInt32(48_000))
    writer.append(UInt16(16))
    writer.append(Saab.layoutHoaAcnSn3d)
    writer.append(Saab.configPreferAutoHeadTracking)
    writer.append(UInt32(1440))
    writer.appendString16("AirPods Pro")
    let configData = writer.data
    let config = configData.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
        SaabCodec.decodeConfig(raw)
    }
    passed = passed && config == BridgeConfig(generation: 7, sampleRate: 48_000, channels: 16, layout: 1, flags: 1, targetFrames: 1440, deviceName: "AirPods Pro")
    let truncated = Data(configData.prefix(configData.count - 1))
    let truncatedConfig = truncated.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
        SaabCodec.decodeConfig(raw)
    }
    passed = passed && truncatedConfig == nil

    // HEAD 解码。
    var headWriter = PacketWriter()
    headWriter.append(Saab.headValid)
    headWriter.appendFloat(0)
    headWriter.appendFloat(0)
    headWriter.appendFloat(0.5)
    headWriter.appendFloat(1)
    let head = headWriter.data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
        SaabCodec.decodeHead(raw)
    }
    passed = passed && head == BridgeHeadPose(valid: true, x: 0, y: 0, z: 0.5, w: 1)

    // STATUS：7 个 32 位字段 + 两个带长度前缀的字符串。
    var status = BridgeStatus()
    status.deviceName = "AirPods"
    status.lastError = ""
    let statusPayload = SaabCodec.statusPayload(status)
    passed = passed && statusPayload.count == 28 + 2 + 7 + 2

    // 头部映射：向左转 90° 时听者面向 −X，头顶仍为 +Y。
    let half = Float.pi / 4
    let turned = listenerOrientation(x: 0, y: 0, z: sin(half), w: cos(half))
    passed = passed && abs(turned.forward.x + 1) < 1e-4 && abs(turned.forward.y) < 1e-4 && abs(turned.forward.z) < 1e-4
    passed = passed && abs(turned.up.y - 1) < 1e-4
    let identity = listenerOrientation(x: 0, y: 0, z: 0, w: 1)
    passed = passed && abs(identity.forward.z + 1) < 1e-6 && abs(identity.up.y - 1) < 1e-6
    return passed
}

/// 环形缓冲区自检：写入、读取、欠载补零与计数。
func ringSelfCheck() -> Bool {
    let ring = AudioRing()
    ring.reset(channels: 2, startThreshold: 4)
    var writer = PacketWriter()
    for index in 0..<8 {
        writer.appendFloat(Float(index))
        writer.appendFloat(Float(-index))
    }
    writer.data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
        if let base = raw.baseAddress {
            ring.writeInterleaved(base, frames: 8, channels: 2)
        }
    }
    let frames = 6
    let left = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    let right = UnsafeMutablePointer<Float>.allocate(capacity: frames)
    left.initialize(repeating: 99, count: frames)
    right.initialize(repeating: 99, count: frames)
    let list = AudioBufferList.allocate(maximumBuffers: 2)
    defer {
        free(list.unsafeMutablePointer)
        left.deallocate()
        right.deallocate()
    }
    list[0] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(frames * MemoryLayout<Float>.size), mData: UnsafeMutableRawPointer(left))
    list[1] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(frames * MemoryLayout<Float>.size), mData: UnsafeMutableRawPointer(right))
    var silence = ObjCBool(false)

    // 第一次：缓冲 8 帧 ≥ 阈值 4，取出 6 帧，剩 2 帧，无欠载。
    renderFromRing(ring.handle, frameCount: frames, outputData: list.unsafeMutablePointer, isSilence: &silence)
    var passed = ring.bufferedFrames() == 2 && ring.underrunCount() == 0 && !silence.boolValue
    for index in 0..<frames {
        passed = passed && left[index] == Float(index) && right[index] == Float(-index)
    }

    // 第二次：只剩 2 帧，其余补零并记一次欠载。
    renderFromRing(ring.handle, frameCount: frames, outputData: list.unsafeMutablePointer, isSilence: &silence)
    passed = passed && left[0] == 6 && left[1] == 7 && right[1] == -7
    for index in 2..<frames {
        passed = passed && left[index] == 0 && right[index] == 0
    }
    passed = passed && ring.underrunCount() == 1 && ring.bufferedFrames() == 0

    // 空闲（0 声道）时输出静音且不计欠载。
    ring.reset(channels: 0, startThreshold: 1)
    renderFromRing(ring.handle, frameCount: frames, outputData: list.unsafeMutablePointer, isSilence: &silence)
    passed = passed && silence.boolValue && ring.underrunCount() == 1 && left[0] == 0
    return passed
}

/// 运行全部自检，返回是否通过。不启动运动更新、不请求权限、不需要音频设备。
func runSelfTest() -> Bool {
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
    reportLine("SpatialAudioHeadTracker 自检")
    reportLine("deviceMotionAvailable=\(manager.isDeviceMotionAvailable)")
    reportLine("authorizationStatus=\(CMHeadphoneMotionManager.authorizationStatus().rawValue)")
    reportLine("packetSize=\(sample.count)")
    let sahtPassed = sample.count == Saht.packetSize && Array(sample.prefix(4)) == Saht.magic

    reportLine("macOSMajor=\(BridgeCapabilities.macOSMajorVersion())")
    reportLine("entitlementHeadPose=\(Entitlements.isPresent(Saab.entitlementHeadPose))")
    reportLine("entitlementProfileAccess=\(Entitlements.isPresent(Saab.entitlementProfileAccess))")
    if #available(macOS 15.0, *) {
        reportLine("listenerHeadTrackingAPI=available")
    } else {
        reportLine("listenerHeadTrackingAPI=unavailable")
    }
    reportLine("capabilities=\(BridgeCapabilities.detect())")

    let protocolPassed = protocolSelfCheck()
    reportLine("saabProtocol=\(protocolPassed ? "PASS" : "FAIL")")
    let ringPassed = ringSelfCheck()
    reportLine("ringBuffer=\(ringPassed ? "PASS" : "FAIL")")
    let renderPassed = renderSelfCheck()
    reportLine("appleSpatialRender=\(renderPassed ? "PASS" : "FAIL")")

    let passed = sahtPassed && protocolPassed && ringPassed && renderPassed
    reportLine("selfTest=\(passed ? "PASS" : "FAIL")")
    return passed
}

// MARK: - 程序入口

let options = Options.parse(CommandLine.arguments)

if options.selfTest {
    // 自检：不启动运动更新，因此不会触发授权弹窗，可在 CI 中运行。
    exit(runSelfTest() ? 0 : 1)
}

// 向已关闭的套接字写入时不要被 SIGPIPE 终止。
signal(SIGPIPE, SIG_IGN)

/// 头部追踪器（--port 0 时为 nil）。
let tracker: HeadTracker?
if options.port == 0 {
    logMessage("--port 0：不启动 CoreMotion，也不发送 SAHT 数据")
    tracker = nil
} else if let sender = UdpSender(port: options.port) {
    let created = HeadTracker(sender: sender)
    created.start()
    tracker = created
} else {
    logMessage("无法创建 UDP 套接字")
    tracker = nil
}

/// SAAB 音频桥客户端（未提供 --audio-port 时为 nil）。
let audioBridge: AudioBridgeClient?
if options.audioPort != 0 {
    if let token = options.audioToken, Options.isValidToken(token) {
        let ring = AudioRing()
        let capabilities = BridgeCapabilities.detect()
        let renderer = AppleSpatialRenderer(ring: ring, capabilities: capabilities)
        let bridge = AudioBridgeClient(port: options.audioPort, token: Array(token.utf8), capabilities: capabilities, ring: ring, renderer: renderer)
        bridge.start()
        audioBridge = bridge
    } else {
        logMessage("--audio-token 缺失或格式无效（需要 32 个小写十六进制字符），不启动 Apple 空间音频渲染")
        audioBridge = nil
    }
} else {
    audioBridge = nil
}

if tracker == nil && audioBridge == nil {
    // 头部追踪与音频渲染都没有启动（UDP 套接字创建失败，或只要求音频却缺少有效令牌）：没有任何事可做，直接退出。
    logMessage("没有需要执行的功能，助手退出")
    exit(1)
}

// 每 0.5 秒发送一次 SAHT 状态，并在游戏进程退出后自动退出。
let statusTimer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
statusTimer.schedule(deadline: .now(), repeating: .milliseconds(500))
statusTimer.setEventHandler {
    tracker?.sendStatus()
    if !parentAlive(options.parentPid) {
        logMessage("游戏进程已退出，助手结束")
        exit(0)
    }
}
statusTimer.resume()

signal(SIGTERM) { _ in
    exit(0)
}

logMessage("已启动：SAHT 端口 \(options.port)，音频桥端口 \(options.audioPort)，父进程 \(options.parentPid)")
RunLoop.main.run()
