import Foundation
import CoreLocation

/// 定位失败的分类（对齐 miuix bd937ef 的 `LocationFailure`）：
/// 便于 UI 给出可操作提示，而不是笼统的「定位失败」。
enum LocationFailure {
    case noPermission
    case locationOff
    case noProvider
    case timeout
    case unknown
}

extension LocationFailure {
    /// 分类 → 用户可读提示（与上游 `locationFailureText` 文案一致）。
    var text: String {
        switch self {
        case .noPermission: return "没有定位权限，请允许 HamKit 使用定位"
        case .locationOff:  return "系统定位已关闭，请打开 GPS 或网络定位"
        case .noProvider:   return "当前没有可用的定位源，请检查 GPS/Wi-Fi"
        case .timeout:      return "定位超时，请到开阔处重试或检查 GPS"
        case .unknown:      return "定位失败，请稍后重试"
        }
    }
}

/// iOS 定位封装：对应 Android `LocationHelper` 的常用子集。
///
/// 行为对齐 miuix bd937ef（缓存秒回、失败分类、可操作提示）：
/// - 缓存秒回：`lastLocation` 精度 ≤50m 且 ≤10min 时直接复用，不等真实定位；
///   后续 `requestLocation` 成功后仍会用新位置替换（与上游「持续监听替换」一致）；
/// - 失败分类：权限被拒 / 系统定位关闭 / 暂时不可用 / 重试耗尽，各自给出可操作提示；
/// - `refreshLocation` 面向 AI 工具 `refresh_location`，轮询等待一次新的定位。
final class LocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {

    @Published private(set) var coordinate: CLLocationCoordinate2D?
    @Published private(set) var statusText: String = "尚未请求"
    /// 最近一次失败分类（nil = 没有失败）；UI 可据此提示。
    @Published private(set) var lastFailure: LocationFailure?

    private let manager = CLLocationManager()
    private var retryCount = 0
    private let maxRetries = 3

    /// 缓存秒回阈值（对齐上游常量）：精度 50m、时效 10 分钟。
    private static let cacheMaxAccuracyMeters: CLLocationAccuracy = 50
    private static let cacheMaxAge: TimeInterval = 10 * 60

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    // MARK: - 缓存秒回（对齐上游 pickBestCached + ≤50m/≤10min 秒回）

    /// 缓存是否可秒回：精度与时效都达标。
    private func isCacheUsable(_ location: CLLocation, now: Date = Date()) -> Bool {
        location.horizontalAccuracy >= 0
            && location.horizontalAccuracy <= Self.cacheMaxAccuracyMeters
            && now.timeIntervalSince(location.timestamp) <= Self.cacheMaxAge
            && now.timeIntervalSince(location.timestamp) >= 0
    }

    // MARK: - 请求入口

    /// 请求前台定位权限并发起一次定位。
    /// 权限已授权且缓存秒回达标时，`coordinate` 立即可用（UI 无需等待）。
    func requestLocation() {
        switch manager.authorizationStatus {
        case .notDetermined:
            statusText = "等待授权"
            lastFailure = nil
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            // 系统定位总开关关闭：预检直接给可操作提示，不再等超时（对齐上游预检）
            if !CLLocationManager.locationServicesEnabled() {
                statusText = LocationFailure.locationOff.text
                lastFailure = .locationOff
                return
            }
            lastFailure = nil
            if let cached = manager.location, isCacheUsable(cached) {
                // 缓存秒回：先给 UI 一个可用的坐标，真实定位稍后自动替换
                apply(cached)
                statusText = "定位中…（先用缓存位置）"
            } else {
                statusText = "定位中…"
            }
            manager.requestLocation()
        case .denied, .restricted:
            statusText = LocationFailure.noPermission.text
            lastFailure = .noPermission
        @unknown default:
            statusText = LocationFailure.unknown.text
        }
    }

    /// 强制重新定位（忽略缓存）：异步发起一次新的 GPS 请求，结果经回调返回。
    ///
    /// `requestLocation()` 只在「拿到更新的位置」时回调，旧缓存不算数，
    /// 与 Android `AiAppFacadeImpl.refreshLocation` 的语义一致：
    /// AI 工具 `refresh_location` 用它把「定位不准」的场景兜住。
    func refreshLocation(timeoutSeconds: TimeInterval = 20, completion: @escaping (CLLocationCoordinate2D?) -> Void) {
        let startedAt = Date()
        var finished = false
        // 记下发起前的缓存坐标：只有出现「更新」的位置才算刷新成功
        let baseline = coordinate
        // 轮询 @Published coordinate：requestLocation 成功后会更新
        func poll() {
            if finished { return }
            if let coordinate, Date().timeIntervalSince(startedAt) > 0.5,
               !Self.samePoint(coordinate, baseline) {
                finished = true
                completion(coordinate)
                return
            }
            // 没拿到更新的位置：半程后允许缓存兜底（对齐上游「超时用 fallback 兜底」）
            if let cached = manager.location,
               Date().timeIntervalSince(startedAt) > timeoutSeconds / 2,
               isCacheUsable(cached) {
                finished = true
                completion(cached.coordinate)
                return
            }
            if Date().timeIntervalSince(startedAt) > timeoutSeconds {
                finished = true
                completion(baseline)
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { poll() }
        }
        requestLocation()
        poll()
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            statusText = "定位中…"
            manager.requestLocation()
        case .denied, .restricted:
            statusText = LocationFailure.noPermission.text
            lastFailure = .noPermission
        case .notDetermined:
            statusText = "等待授权"
        @unknown default:
            statusText = LocationFailure.unknown.text
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        retryCount = 0
        lastFailure = nil
        apply(last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let nsError = error as NSError
        // kCLErrorLocationUnknown（code 0）：位置暂时不可用。
        // 模拟器没设位置、或真机刚进室内/隧道时非常常见，属于「预期内的暂时失败」，
        // 不应该把系统错误直接甩到界面上。静默重试几次，仍失败才提示。
        if nsError.code == CLError.locationUnknown.rawValue {
            retryCount += 1
            if retryCount <= maxRetries {
                statusText = "定位中…（等待信号 \(retryCount)/\(maxRetries)）"
                let currentRetry = retryCount
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                    guard let self, self.retryCount == currentRetry else { return }
                    self.manager.requestLocation()
                }
            } else {
                // 重试耗尽：允许缓存兜底（精度放宽到秒回阈值内），否则归为 timeout
                if let cached = manager.location, isCacheUsable(cached) {
                    apply(cached)
                    statusText = "暂时拿不到新定位，已使用最近的缓存位置"
                } else {
                    statusText = LocationFailure.timeout.text
                    lastFailure = .timeout
                }
            }
            return
        }
        // 其余错误按分类给出可操作提示（对齐上游 locationFailureText）
        // 注：CLError 没有 locationUnavailable case（那是 CLRegionError 时代的误记），
        // 「拿不到位置」会走上面的 locationUnknown 重试分支；这里只需处理权限与其余。
        let failure: LocationFailure
        switch nsError.code {
        case CLError.denied.rawValue:
            failure = .noPermission
        default:
            failure = .unknown
        }
        statusText = failure.text
        lastFailure = failure
    }

    // MARK: - 私有

    /// CLLocationCoordinate2D 不符合 Equatable，按精度阈值比较两个坐标是否一致。
    private static func samePoint(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D?) -> Bool {
        guard let b else { return false }
        return abs(a.latitude - b.latitude) < 1e-9 && abs(a.longitude - b.longitude) < 1e-9
    }

    private func apply(_ location: CLLocation) {
        coordinate = location.coordinate
        statusText = String(
            format: "%.4f, %.4f（±%.0fm）",
            location.coordinate.latitude,
            location.coordinate.longitude,
            location.horizontalAccuracy
        )
    }
}
