import Foundation

/// 高德 Web 服务 Key（天气 + 逆地理编码）。
///
/// 按项目要求直接写死在源码里：该 Key 随 App 分发、不是秘密，
/// 安全性依赖高德控制台的配额与用量监控。
enum AmapKey {
    static let web = "9d99ce60cc6dea925db6c009d9d2a430"
}