import Foundation
import AVFoundation

/// 统一的音频会话配置。
///
/// 后台音频依赖 Info.plist 的 `UIBackgroundModes: audio`；这里用 `.playAndRecord`
/// 并允许混音，保证 CW/FT8/SSTV/HF Fax 在前台录音、切后台后系统不立即暂停
/// （实际是否继续由系统决定，锁屏后仍可能被回收）。
enum AudioSession {

    static func activateRecording() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playAndRecord,
            mode: .measurement,
            options: [.defaultToSpeaker, .allowBluetooth, .mixWithOthers]
        )
        try session.setActive(true, options: [])
    }

    static func activatePlayback() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            .playback,
            mode: .default,
            options: [.mixWithOthers]
        )
        try session.setActive(true, options: [])
    }

    /// App 进入后台/回前台时刷新激活状态，避免会话被系统降级为静音。
    static func refreshIfNeeded() {
        let session = AVAudioSession.sharedInstance()
        if session.category == .playAndRecord || session.category == .playback {
            try? session.setActive(true, options: [])
        }
    }
}
