import SwiftUI
import MapKit
import Shared

/// 卫星链路全部子页：管理（列表）/ 筛选 / 详情 / 雷达 / 轨道地图 / 提醒列表 / 定位详情。
/// 对应 Android 的 satellite、reminder、location 三个 screen 目录。

// MARK: - 卫星管理（列表）

// MARK: - 卫星筛选

/// 卫星筛选页：对应 Android SatelliteFilter —— 按收藏/名称搜索过滤，选中即设为当前卫星。
// MARK: - 雷达图

/// 雷达页：对应 Android SatelliteRadar —— 全屏极坐标雷达。
struct SatelliteRadarView: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    var body: some View {
        VStack(spacing: 16) {
            if let position = model.position, model.selectedNorad == norad {
                RadarView(current: position, track: model.track)
                    .frame(maxHeight: 360)
                    .padding()
                HStack {
                    Text("仰角 \(String(format: "%.1f°", position.elevationDeg))")
                    Text("方位 \(String(format: "%.1f°", position.azimuthDeg))")
                }
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
            } else {
                Text("等待位置解算…").foregroundStyle(.secondary)
            }
            Spacer()
        }
        .navigationTitle("雷达图")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if model.selectedNorad != norad {
                model.selectSatellite(norad: norad)
            }
        }
    }
}

// MARK: - 过境提醒列表

/// 提醒列表页：对应 Android ReminderList —— 列出当前卫星未来过境并可一键设提醒。
struct ReminderListView: View {

    @ObservedObject var model: TrackerModel
    @State private var scheduledIds: Set<Int64> = []
    @State private var message: String?

    var body: some View {
        List {
            Section {
                if model.passes.isEmpty {
                    Text(model.isSearchingPass ? "正在搜索…" : "48 小时内没有过境")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.passes, id: \.aosMs) { pass in
                        PassRow(
                            pass: pass,
                            satelliteName: model.selectedSatelliteName,
                            onRemind: { scheduleReminder(for: pass) },
                            isReminded: scheduledIds.contains(pass.aosMs)
                        )
                    }
                }
            } header: {
                Text("当前卫星 · \(model.selectedSatelliteName)")
            } footer: {
                if let message { Text(message).foregroundStyle(Color.accentColor) }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("过境提醒")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    model.searchNextPass()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
    }

    /// 先确认通知权限（首次会弹窗，且要知道用户是否拒绝），再排程并回传结果。
    /// 之前是先排程再请求授权、且不看授权结果：权限被拒时界面仍显示「已设提醒」，
    /// 实际上一条通知都收不到。
    private func scheduleReminder(for pass: PassSnapshot) {
        guard let norad = model.selectedNorad else { return }
        let settings = ReminderSettingsStore.shared
        NotificationManager.shared.requestAuthorizationIfNeeded { granted in
            DispatchQueue.main.async {
                guard granted else {
                    message = "通知权限被拒绝，请到「设置 → HamKit → 通知」开启"
                    return
                }
                let ok = NotificationManager.shared.schedulePassReminder(
                    satelliteName: model.selectedSatelliteName,
                    norad: norad,
                    aosMs: pass.aosMs,
                    leadSeconds: settings.leadSeconds
                )
                if ok {
                    scheduledIds.insert(pass.aosMs)
                    message = "已设提醒（AOS 前 \(settings.leadMinutes) 分钟）"
                } else {
                    message = "这次过境来不及提醒"
                }
            }
        }
    }
}

// MARK: - 过境行（首页 / 详情 / 提醒列表共用）

/// 单条过境：AOS 时间 + 在境徽标 + 最大仰角/方位，对应 Android 过境列表行。
struct PassRow: View {

    let pass: PassSnapshot
    let satelliteName: String
    let onRemind: () -> Void
    /// 是否已为这次过境设过提醒（铃铛填充态）；首页等未接提醒的调用点保持 false。
    var isReminded: Bool = false

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm:ss"
        formatter.locale = Locale(identifier: "zh_CN")
        return formatter
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(Self.timeFormatter.string(from: pass.aosMs.date))
                    .font(.headline)
                    .monospacedDigit()
                if pass.isCurrentlyVisible {
                    Text("在境")
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.green.opacity(0.15))
                        .foregroundStyle(.green)
                        .clipShape(Capsule())
                }
                Spacer()
                Button(action: onRemind) {
                    Image(systemName: isReminded ? "bell.fill" : "bell")
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("为这次过境设置提醒")
            }
            Text("LOS \(Self.timeFormatter.string(from: pass.losMs.date)) · 最大仰角 \(String(format: "%.1f°", pass.maxElevationDeg)) · \(pass.durationSeconds / 60) 分 \(pass.durationSeconds % 60) 秒")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text("方位 \(pass.aosAzimuthDeg)° → \(pass.losAzimuthDeg)°")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

private extension Int64 {
    var date: Date { Date(timeIntervalSince1970: Double(self) / 1000.0) }
}

// MARK: - 手动坐标编辑

/// 手动编辑地面站坐标：对应 Android 定位详情里的「手动输入」。
/// 保存后走与自动定位相同的 `updateStation` 链路，分区 / 雷达 / 过境全量重算。
struct StationEditView: View {

    @ObservedObject var model: TrackerModel
    @Environment(\.dismiss) private var dismiss

    @State private var latitudeText: String = ""
    @State private var longitudeText: String = ""
    @State private var altitudeText: String = ""
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section {
                TextField("纬度（-90 ~ 90）", text: $latitudeText)
                    .keyboardType(.numbersAndPunctuation)
                TextField("经度（-180 ~ 180）", text: $longitudeText)
                    .keyboardType(.numbersAndPunctuation)
                TextField("海拔（米）", text: $altitudeText)
                    .keyboardType(.numbersAndPunctuation)
            } header: {
                Text("地面站坐标")
            } footer: {
                if let errorMessage {
                    Text(errorMessage).foregroundStyle(.red)
                }
            }

            Section {
                Button("保存并重算") { save() }
            }
        }
        .navigationTitle("地面站")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if latitudeText.isEmpty {
                latitudeText = String(format: "%.4f", model.latitude)
                longitudeText = String(format: "%.4f", model.longitude)
                altitudeText = String(format: "%.0f", model.altitudeM)
            }
        }
    }

    private func save() {
        guard let lat = Double(latitudeText), lat >= -90, lat <= 90,
              let lon = Double(longitudeText), lon >= -180, lon <= 180 else {
            errorMessage = "纬度或经度格式不对"
            return
        }
        let altitude = Double(altitudeText) ?? 0
        model.altitudeM = altitude
        model.updateStation(latitude: lat, longitude: lon)
        dismiss()
    }
}

// MARK: - 定位详情

/// 定位详情页：对应 Android LocationDetail —— 坐标展示 + 手动编辑 + 地图。
struct LocationDetailView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider

    /// 地图视野用 @State：`.constant(...)` 会让拖动后立刻弹回（看起来像拖不动）
    @State private var mapRegion = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 39.9042, longitude: 116.4074),
        span: MKCoordinateSpan(latitudeDelta: 10, longitudeDelta: 10)
    )
    @State private var didCenterMap = false

    var body: some View {
        List {
            Section("当前定位") {
                LabeledContent("来源") { Text(location.statusText) }
                LabeledContent("纬度") { Text(String(format: "%.4f°", model.latitude)).monospacedDigit() }
                LabeledContent("经度") { Text(String(format: "%.4f°", model.longitude)).monospacedDigit() }
                LabeledContent("海拔") { Text(String(format: "%.0f m", model.altitudeM)).monospacedDigit() }
                Button {
                    location.requestLocation()
                } label: {
                    Label("重新定位", systemImage: "location.fill")
                }
            }

            Section("分区") {
                LabeledContent("Maidenhead") { Text(model.zone?.grid ?? "—").monospaced() }
                LabeledContent("CQ 分区", value: zoneText(model.zone?.cqZone))
                LabeledContent("ITU 分区", value: zoneText(model.zone?.ituZone))
            }

            Section {
                NavigationLink {
                    StationEditView(model: model)
                } label: {
                    Label("手动修改坐标", systemImage: "pencil")
                }
            }

            Section {
                Map(coordinateRegion: $mapRegion, interactionModes: .all)
                .frame(height: 220)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .listRowInsets(EdgeInsets())
                .overlay(alignment: .bottomTrailing) {
                    Button {
                        mapRegion.center = CLLocationCoordinate2D(
                            latitude: model.latitude,
                            longitude: model.longitude
                        )
                    } label: {
                        Image(systemName: "scope")
                            .font(.system(size: 13, weight: .semibold))
                            .padding(8)
                            .background(.thinMaterial)
                            .clipShape(Circle())
                    }
                    .padding(10)
                }
                .onAppear {
                    // 只在第一次进入时把视野放到当前地面站，之后完全交给用户拖
                    guard !didCenterMap else { return }
                    didCenterMap = true
                    mapRegion.center = CLLocationCoordinate2D(
                        latitude: model.latitude,
                        longitude: model.longitude
                    )
                }
            } header: {
                Text("位置地图（MapKit）")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("定位详情")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func zoneText(_ value: Int32?) -> String {
        guard let value, value > 0 else { return "未知" }
        return "\(value)"
    }
}
