import Foundation
import Combine
import Shared

/// iOS 壳的 ViewModel：
/// 地面站 → 分区定位 → CelesTrak TLE 拉取 → 卫星实时位置 → 下一次过境。
///
/// 所有计算走共享 Kotlin 内核（`Shared.framework` 的 `HamKitApi`），Swift 只负责
/// 交互状态与网络实现（见 `Networking.swift`）。
final class TrackerModel: ObservableObject {

    // MARK: - 地面站

    @Published var latitude: Double = 39.9042
    @Published var longitude: Double = 116.4074
    @Published var altitudeM: Double = 50

    // MARK: - 分区

    @Published private(set) var zone: ZoneSnapshot?

    // MARK: - 卫星数据（tle.hamkit.click，与 Android 同源）

    /// 全量目录（satnogs + amateur + active 合并，1.6 万+ 颗）：卫星管理/筛选/分类用。
    @Published private(set) var satellites: [TleRecord] = []
    /// 业余子集（satnogs + amateur，约 600 颗）：首页「附近过境」、提醒、默认选中用。
    ///
    /// 与 Android 一致：过境预测只跑业余星，全量目录里的 LAGEOS / COSMOS 之类
    /// 不参与「附近过境」，否则既不对、又要把跨星预测拖到几十秒。
    @Published private(set) var amateurSatellites: [TleRecord] = []
    @Published private(set) var selectedNorad: Int32?
    @Published private(set) var dataStatusText: String = "尚未加载"
    @Published private(set) var lastUpdatedText: String = "—"
    @Published private(set) var isLoadingSatellites = false

    // MARK: - 实时

    @Published private(set) var position: PositionSnapshot?
    /// 雷达图用的轨迹（前后各 45 分钟，60 秒步长）
    @Published private(set) var track: [PositionSnapshot] = []
    /// 未来 48 小时的前 3 次过境
    @Published private(set) var passes: [PassSnapshot] = []
    /// 附近过境卫星：每颗卫星的下一次过境（在境优先、按 AOS 排序），对齐 Android 逻辑
    @Published private(set) var nearbyPasses: [Shared.SatelliteNextPass] = []
    @Published private(set) var isSearchingPass = false
    /// 首页「附近过境」跨星预测是否在跑（与单星搜索分开，避免互相盖住状态）
    @Published private(set) var isSearchingNearby = false
    @Published private(set) var favorites: Set<Int32> = []
    /// SatNOGS 转发器：NORAD → 该星全部转发器（含 inactive）
    @Published private(set) var transmittersByNorad: [Int32: [Transmitter]] = [:]
    @Published private(set) var isTransmittersLoading = false
    /// 转发器拉取失败的原因（非空时详情页给出「重试」入口）
    @Published private(set) var transmitterError: String?
    /// AMSAT 状态：AMSAT 名称（如 "AO-91_[FM]"）→ 状态值
    @Published private(set) var amsatStatusByName: [String: String] = [:]
    @Published private(set) var errorText: String?

    private var timer: Timer?
    private var passRequestId = 0
    private var nearbyRequestId = 0
    private var nearbyFingerprint = ""
    private var nearbyWorkItem: DispatchWorkItem?
    private var trackRequestId = 0
    private var tickCount = 0

    private let cache = TleDiskCache()
    private let amateurCache = TleDiskCache(filename: "tle-cache-amateur.json")
    private let transmitterCache = TransmitterDiskCache()
    private static let favoritesKey = "hamkit.favorites"

    var isSelectedFavorite: Bool {
        guard let norad = selectedNorad else { return false }
        return favorites.contains(norad)
    }

    init() {
        // 网络实现必须在第一次 fetchTles 之前注入
        HamKitApi.shared.installHttpTransport(transport: URLSessionTransport())
        favorites = Set((UserDefaults.standard.array(forKey: Self.favoritesKey) as? [Int] ?? []).map { Int32($0) })
    }

    // MARK: - 派生状态

    var selectedSatellite: TleRecord? {
        guard let norad = selectedNorad else { return nil }
        return satellites.first { $0.norad == norad }
    }

    var selectedSatelliteName: String {
        selectedSatellite?.name ?? "未选择卫星"
    }

    /// 列表页用：NORAD → 下一次过境（来自跨星预测，含当前在境）
    var passByNorad: [Int32: PassSnapshot] {
        var map: [Int32: PassSnapshot] = [:]
        for item in nearbyPasses {
            map[Int32(item.norad)] = item.pass
        }
        // 当前选中卫星的单星三次过境以 model.passes 为准（更精确）
        if let norad = selectedNorad, let first = passes.first {
            map[norad] = first
        }
        return map
    }

    private var tleLines: [String] {
        selectedSatellite?.lines ?? []
    }

    /// 首页过境的计算范围：业余子集；冷启动还没拿到业余源时退到全量目录的前
    /// 300 颗（设上限是为了绝不把 1.6 万颗丢进跨星预测）。
    private var nearbySatellites: [TleRecord] {
        if !amateurSatellites.isEmpty { return amateurSatellites }
        return Array(satellites.prefix(Self.nearbyFallbackLimit))
    }

    // MARK: - 生命周期

    func start() {
        guard timer == nil else { return }
        refreshZone()
        restoreCachedSatellites()
        loadSatellites(force: false)
        // 先上磁盘缓存：SatNOGS 全量几 MB，弱网/被墙时不该让详情页空着。
        restoreCachedTransmitters()
        loadTransmitters()
        loadAmsatStatus()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.tickPosition()
        }
    }

    /// 冷启动先恢复上次的转发器（频率），网络刷新回来后再覆盖。
    private func restoreCachedTransmitters() {
        guard let cached = transmitterCache.load() else { return }
        transmittersByNorad = Self.group(cached)
    }

    /// 设置页展示用：转发器数据处于什么状态（拉取中 / 失败原因 / 已就绪）。
    var transmitterStatusText: String {
        if isTransmittersLoading { return "正在拉取…" }
        if let error = transmitterError { return "失败：\(error)" }
        if transmittersByNorad.isEmpty { return "暂无数据" }
        return "\(Self.countText(transmittersByNorad.count)) 颗卫星有频率"
    }

    /// 拉取 AMSAT 状态摘要（每颗卫星一条代表状态），失败静默降级。
    func loadAmsatStatus() {
        HamKitApi.shared.fetchAmsatStatus { [weak self] snapshot in
            guard let self, snapshot.error == nil else { return }
            var map: [String: String] = [:]
            for item in snapshot.summaries {
                map[item.name] = item.status
            }
            DispatchQueue.main.async {
                self.amsatStatusByName = map
            }
        }
    }

    /// 某颗卫星的 AMSAT 状态；目录中没有该星或未拉取到状态时返回 nil。
    func amsatStatus(norad: Int32) -> String? {
        let name = HamKitApi.shared.amsatStatusName(norad: norad)
        guard !name.isEmpty else { return nil }
        return amsatStatusByName[name]
    }

    /// 拉取 SatNOGS 转发器（列表卡片的模式/数量徽章、详情页的上下行频率依赖它）。
    ///
    /// 这份数据是几 MB 的全量 JSON，弱网下容易超时；之前失败是「静默 return」，
    /// 结果就是详情页永远显示「暂无转发器数据 / 数据不可用」，用户完全不知道
    /// 是没拉到还是这颗星真的没有。现在：保留错误文案 + 自动重试一次 + 详情页
    /// 可以手动重试。
    func loadTransmitters() {
        guard !isTransmittersLoading else { return }
        isTransmittersLoading = true
        fetchTransmitters(attempt: 0)
    }

    private func fetchTransmitters(attempt: Int) {
        HamKitApi.shared.fetchTransmitters { [weak self] snapshot in
            guard let self else { return }
            NSLog(
                "[HamKit] transmitters attempt=\(attempt) count=\(snapshot.transmitters.count) error=\(snapshot.error ?? "-")"
            )
            let grouped = Self.group(snapshot.transmitters)
            DispatchQueue.main.async {
                self.isTransmittersLoading = false
                if grouped.isEmpty, let error = snapshot.error {
                    self.transmitterError = error
                    // SatNOGS 偶发超时/被限流：等 5s 自动再试一次，
                    // 用户不该为了几 MB 的转发器表反复重进 App。
                    if attempt < 1 {
                        self.isTransmittersLoading = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                            self.fetchTransmitters(attempt: attempt + 1)
                        }
                    }
                    return
                }
                self.transmitterError = nil
                if !grouped.isEmpty {
                    self.transmittersByNorad = grouped
                    // 几 MB 的 JSON 编码放到后台，别卡住主线程
                    let records = snapshot.transmitters
                    DispatchQueue.global(qos: .utility).async { [weak self] in
                        self?.transmitterCache.save(records)
                    }
                }
            }
        }
    }

    private static func group(_ transmitters: [Transmitter]) -> [Int32: [Transmitter]] {
        var grouped: [Int32: [Transmitter]] = [:]
        for transmitter in transmitters {
            grouped[Int32(transmitter.norad), default: []].append(transmitter)
        }
        return grouped
    }

    /// 冷启动先上磁盘缓存，界面立刻有数据；网络结果回来后再替换。
    private func restoreCachedSatellites() {
        // 业余子集先恢复：首页过境只依赖它，不必等全量目录的 3MB 读完。
        if let amateur = amateurCache.load() {
            amateurSatellites = amateur.records
        }
        guard let cached = cache.load() else {
            if !amateurSatellites.isEmpty {
                satellites = amateurSatellites
            }
            return
        }
        satellites = cached.records
        let date = Date(timeIntervalSince1970: Double(cached.fetchedAtMs) / 1000)
        lastUpdatedText = Self.timeFormatter.string(from: date)
        dataStatusText = "本地缓存 · \(Self.countText(cached.records.count)) 颗（刷新中…）"
        selectDefaultIfNeeded()
        tickPosition()
        refreshTrack()
        searchNextPass()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// 地面站变化后重算全部分区与过境。
    func updateStation(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
        refreshZone()
        tickPosition()
        refreshTrack()
        searchNextPass()
    }

    // MARK: - TLE 拉取

    /// 拉取**完整卫星目录**，与 Android `SatelliteDataSource` 同源同优先级：
    /// `amateur`（≈100）→ `satnogs`（≈650）→ `active`（1.6 万+，只补全前面没有的 NORAD）。
    ///
    /// 共享内核按分组逐步回调：
    /// 1. `catalogComplete == false`：业余源到手（约 100～650 颗）→ 首页、过境、提醒立刻可用；
    /// 2. `catalogComplete == true`：并入 active 后的全量 → 卫星管理/筛选/分类用。
    ///
    /// 之前 iOS 只拉 `amateur`（约 95 颗），是为了绕开「全量目录把 LAGEOS/COSMOS
    /// 排成在境」的问题；正确解法是**数据源用全量、过境只算业余星**，而不是砍掉数据。
    func loadSatellites(force: Bool) {
        guard !isLoadingSatellites else { return }
        isLoadingSatellites = true
        dataStatusText = force ? "正在从 tle.hamkit.click 拉取…" : "加载中…"

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        HamKitApi.shared.fetchCatalog(forceRefresh: force, nowMs: nowMs) { [weak self] snapshot in
            DispatchQueue.main.async {
                guard let self else { return }
                let records = snapshot.records

                guard !records.isEmpty else {
                    // 业余轮就空了 → 继续等 active 轮；全量轮也空才认输。
                    guard snapshot.catalogComplete else { return }
                    self.isLoadingSatellites = false
                    if self.satellites.isEmpty { self.satellites = [Self.fallbackTle] }
                    self.lastUpdatedText = "内置示例"
                    self.dataStatusText = "离线 · 内置示例 TLE（\(snapshot.error ?? "无数据")）"
                    self.selectDefaultIfNeeded()
                    self.tickPosition()
                    self.refreshTrack()
                    self.searchNextPass()
                    return
                }

                if snapshot.fetchedAtMs > 0 {
                    self.lastUpdatedText = Self.timeFormatter.string(
                        from: Date(timeIntervalSince1970: Double(snapshot.fetchedAtMs) / 1000)
                    )
                }

                if snapshot.catalogComplete {
                    self.isLoadingSatellites = false
                    self.satellites = records
                    var status = (snapshot.fromCache ? "缓存" : "已更新")
                        + " · \(Self.countText(records.count)) 颗"
                    if let error = snapshot.error {
                        status += "（\(error)）"
                    }
                    self.dataStatusText = status
                    // 只有拿到全量目录才写盘：几百条的部分结果不能盖掉已有的 1.6 万+。
                    if snapshot.error == nil {
                        self.cache.save(records: records, fetchedAtMs: snapshot.fetchedAtMs)
                    }
                } else {
                    // 业余子集：首页/过境/提醒的唯一输入，单独缓存（约 100KB）。
                    self.amateurSatellites = records
                    self.amateurCache.save(records: records, fetchedAtMs: snapshot.fetchedAtMs)
                    if self.satellites.isEmpty {
                        self.satellites = records
                    }
                    self.dataStatusText = "已更新 · \(Self.countText(records.count)) 颗（正在拉取全量目录…）"
                }

                self.selectDefaultIfNeeded()
                self.tickPosition()
                self.refreshTrack()
                self.searchNextPass()
            }
        }
    }

    func selectSatellite(norad: Int32) {
        guard norad != selectedNorad else { return }
        selectedNorad = norad
        tickPosition()
        refreshTrack()
        searchNextPass()
    }

    private func selectDefaultIfNeeded() {
        if let norad = selectedNorad, satellites.contains(where: { $0.norad == norad }) {
            return
        }
        // 优先 ISS（NORAD 25544），否则第一颗
        selectedNorad = satellites.first(where: { $0.norad == 25544 })?.norad
            ?? satellites.first?.norad
    }

    // MARK: - 共享内核调用

    private func refreshZone() {
        do {
            zone = try HamKitApi.shared.zone(latitude: latitude, longitude: longitude)
            errorText = nil
        } catch {
            zone = nil
            errorText = "坐标非法：\(error.localizedDescription)"
        }
    }

    private func tickPosition() {
        let tle = tleLines
        guard !tle.isEmpty else {
            position = nil
            return
        }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        position = HamKitApi.shared.satellitePosition(
            tle: tle,
            latitude: latitude,
            longitude: longitude,
            altitudeM: altitudeM,
            atMs: nowMs
        )

        // 轨迹不用每秒重算：每 30 秒刷新一次即可
        tickCount += 1
        if tickCount % 30 == 0 {
            refreshTrack()
        }
    }

    /// 计算雷达图轨迹（前后各 45 分钟、60 秒步长，约 91 个点）。
    /// 放在后台队列，避免选星瞬间卡顿。
    func refreshTrack() {
        let tle = tleLines
        guard !tle.isEmpty else {
            track = []
            return
        }
        trackRequestId += 1
        let requestId = trackRequestId
        let lat = latitude
        let lon = longitude
        let alt = altitudeM

        DispatchQueue.global(qos: .utility).async { [weak self] in
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let points = HamKitApi.shared.satelliteTrack(
                tle: tle,
                latitude: lat,
                longitude: lon,
                altitudeM: alt,
                fromMs: nowMs - 45 * 60 * 1000,
                toMs: nowMs + 45 * 60 * 1000,
                stepSeconds: 60
            )
            DispatchQueue.main.async {
                guard let self, requestId == self.trackRequestId else { return }
                self.track = points
            }
        }
    }

    /// 48 小时过境搜索约 600 次传播计算，放到后台队列避免卡住 UI。
    /// 用请求序号而不是布尔守卫：快速连续切星时，旧请求结果按序号丢弃，
    /// 新星总能拿到属于自己的搜索。
    func searchNextPass() {
        let tle = tleLines
        guard !tle.isEmpty else { return }

        isSearchingPass = true
        passRequestId += 1
        let requestId = passRequestId
        let lat = latitude
        let lon = longitude
        let alt = altitudeM

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let result = HamKitApi.shared.nextPasses(
                tle: tle,
                latitude: lat,
                longitude: lon,
                altitudeM: alt,
                fromMs: nowMs,
                searchHours: 48,
                maxCount: 3
            )
            DispatchQueue.main.async {
                guard let self, requestId == self.passRequestId else { return }
                self.passes = result
                self.isSearchingPass = false
            }
        }
        // 跨星预测独立跑（结果与当前选中星无关），不阻塞单星过境
        searchNearbyPasses()
    }

    /// 附近过境卫星：跨星各算下一次过境（对齐 Android SatellitePredictor）。
    ///
    /// 拉取过程中内核会连发几次目录更新（amateur → satnogs → active），每次都
    /// 把 600 多颗星重算一遍纯属浪费，所以这里做 0.8s 防抖：只对最后一次算。
    func searchNearbyPasses() {
        nearbyWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.performNearbySearch() }
        nearbyWorkItem = work
        // 先去点亮加载态：防抖的 0.8s 里首页不该闪一下「暂无过境」。
        isSearchingNearby = !nearbySatellites.isEmpty
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8, execute: work)
    }

    private func performNearbySearch() {
        // 只对业余星算过境：与 Android 一致，也避免 1.6 万颗 × 48 小时跑几十秒。
        let snapshot = nearbySatellites
        guard !snapshot.isEmpty else {
            isSearchingNearby = false
            return
        }
        let fingerprint = "\(snapshot.count)-\(snapshot.first?.norad ?? 0)-\(snapshot.last?.norad ?? 0)"
            + "-\(Int(latitude * 100))-\(Int(longitude * 100))"
        if fingerprint == nearbyFingerprint {
            isSearchingNearby = false
            return
        }
        nearbyFingerprint = fingerprint

        nearbyRequestId += 1
        let requestId = nearbyRequestId
        let lat = latitude
        let lon = longitude
        let alt = altitudeM
        isSearchingNearby = true

        DispatchQueue.global(qos: .utility).async { [weak self] in
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let nearby = HamKitApi.shared.nextPassesAcrossSatellites(
                tles: snapshot,
                latitude: lat,
                longitude: lon,
                altitudeM: alt,
                fromMs: nowMs,
                searchHours: 48,
                maxCount: 300
            )
            DispatchQueue.main.async {
                guard let self, requestId == self.nearbyRequestId else { return }
                self.nearbyPasses = nearby
                self.isSearchingNearby = false
                self.refreshScheduledReminders()
            }
        }
    }

    /// 按分类配置里的「卫星级提醒开关」为下一次过境重建本地通知。
    /// 对齐 Android `refreshRemindersFromPrediction`；iOS 只能在 App 运行时刷新，
    /// 后台刷新由系统调度近似（见 docs/IOS_ADAPTATION.md）。
    private func refreshScheduledReminders() {
        let settings = ReminderSettingsStore.shared
        // 先清掉旧通知再按当前设置重建：必须等删除完成再排新通知，
        // 否则异步的 removePendingNotificationRequests 会把刚排上的新提醒删掉。
        NotificationManager.shared.cancelPassReminders { [weak self] in
            self?.rebuildScheduledReminders(settings: settings)
        }
    }

    private func rebuildScheduledReminders(settings: ReminderSettingsStore) {
        guard settings.enabled else {
            BackgroundReminderScheduler.shared.updatePlans([])
            return
        }
        let flags = SatelliteCategoryManager.shared.snapshot.reminderFlags
        guard !flags.isEmpty else {
            BackgroundReminderScheduler.shared.updatePlans([])
            return
        }

        var plans: [BackgroundReminderScheduler.Plan] = []
        for item in nearbyPasses where flags.contains(Int32(item.norad)) {
            plans.append(
                BackgroundReminderScheduler.Plan(
                    satelliteName: item.name,
                    aosMs: item.pass.aosMs,
                    norad: Int32(item.norad)
                )
            )
            NotificationManager.shared.schedulePassReminder(
                satelliteName: item.name,
                norad: Int32(item.norad),
                aosMs: item.pass.aosMs,
                leadSeconds: settings.leadSeconds
            )
        }
        // 交给 BGAppRefreshTask 在后台重建通知
        BackgroundReminderScheduler.shared.updatePlans(plans)
        BackgroundReminderScheduler.shared.schedule()
    }

    /// 设置页改了「启用提醒 / 提前量」后立即生效，不用等下一轮过境计算。
    func applyReminderSettings() {
        refreshScheduledReminders()
    }

    // MARK: - 收藏

    func toggleFavorite(_ norad: Int32) {
        if favorites.contains(norad) {
            favorites.remove(norad)
        } else {
            favorites.insert(norad)
        }
        UserDefaults.standard.set(favorites.map { Int($0) }, forKey: Self.favoritesKey)
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        formatter.locale = Locale(identifier: "zh_CN")
        return formatter
    }()

    /// 16900 → "16,900"：全量目录上万颗时读起来更直观。
    private static let countFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter
    }()

    private static func countText(_ count: Int) -> String {
        countFormatter.string(from: NSNumber(value: count)) ?? "\(count)"
    }

    /// 缺少业余源时首页过境的回退上限（颗）。
    private static let nearbyFallbackLimit = 300

    /// 离线兜底：2026-08-09 拉取的 ISS TLE。
    /// 只在「从未成功联网」时使用，联网后会立刻被 CelesTrak 数据替换。
    private static let fallbackTle = TleRecord(
        name: "ISS (ZARYA) · 内置",
        norad: 25544,
        line1: "1 25544U 98067A   26220.50489838  .00004539  00000+0  89319-4 0  9991",
        line2: "2 25544  51.6323  41.1734 0007358  25.6275 334.5077 15.49385107579869"
    )
}
