import SwiftUI
import MapKit
import Shared

/// APRS 主页面：对应 Android `AprsMain` —— 连接状态、收发开关、去各子页。
struct AprsMainView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider
    @ObservedObject var store = AprsStore.shared

    var body: some View {
        List {
            Section {
                LabeledContent("呼号", value: store.config.fullCallsign.isEmpty ? "未设置" : store.config.fullCallsign)
                LabeledContent("服务器", value: "\(store.config.server):\(store.config.port)")
                LabeledContent("模式", value: store.config.isReadOnly ? "只读（未填 passcode）" : "可发送")
                LabeledContent("状态", value: store.statusText)
            } header: {
                Text("APRS-IS")
            } footer: {
                Text("本应用不计算、不内置 passcode，只使用你自己持有的验证码；留空即 pass -1 只读连接。")
            }

            Section {
                Button(store.isConnected ? "断开连接" : "连接 APRS-IS") {
                    if store.isConnected {
                        store.disconnect()
                    } else {
                        store.connect()
                    }
                }
                if store.isConnected {
                    Button("发送当前位置") {
                        store.sendPosition(latitude: model.latitude, longitude: model.longitude)
                    }
                    .disabled(!store.config.enableTransmit || store.config.isReadOnly)
                }
            }

            Section("数据") {
                NavigationLink(value: Route.aprsStations) {
                    LabeledContent("站点", value: "\(store.stations.count)")
                }
                NavigationLink(value: Route.aprsMessages) {
                    LabeledContent("消息", value: "\(store.messages.count)")
                }
                NavigationLink(value: Route.aprsMap) {
                    Label("站点地图", systemImage: "map")
                }
                NavigationLink(value: Route.aprsSymbolPicker) {
                    LabeledContent("APRS 符号", value: store.config.symbolTable + store.config.symbolCode)
                }
            }

            Section {
                if store.log.isEmpty {
                    Text("暂无日志").font(.footnote).foregroundStyle(.secondary)
                } else {
                    ForEach(Array(store.log.suffix(20).enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    Button("清空日志") { store.clearLog() }
                        .font(.footnote)
                }
            } header: {
                Text("连接日志")
            } footer: {
                Text("与服务器的原始收发记录（密码已打码）。连不上或被踢时，`#` 开头的那几行就是服务器给的原因。")
            }

            Section {
                NavigationLink(value: Route.aprsSettings) {
                    Label("APRS 设置", systemImage: "gearshape")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("APRS")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            store.updatePosition(latitude: model.latitude, longitude: model.longitude)
        }
        .onReceive(model.$latitude.dropFirst()) { _ in
            store.updatePosition(latitude: model.latitude, longitude: model.longitude)
        }
    }
}

/// APRS 设置：对应 Android `AprsSettings`。
struct AprsSettingsView: View {

    @ObservedObject var store = AprsStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var draft = AprsConfig()

    var body: some View {
        Form {
            Section("台站") {
                TextField("呼号（必填）", text: $draft.callsign)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                TextField("SSID（可空）", text: $draft.ssid)
                SecureField("passcode（留空 = 只读）", text: $draft.passcode)
            }

            Section("服务器") {
                TextField("主机", text: $draft.server)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("端口", value: $draft.port, format: .number)
                    .keyboardType(.numberPad)
                TextField("过滤条件（可选）", text: $draft.filter)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }

            Section("发射") {
                Toggle("允许发射（需要有效 passcode）", isOn: $draft.enableTransmit)
                Stepper("发射间隔 \(draft.transmitInterval) 秒", value: $draft.transmitInterval, in: 30...600, step: 30)
                Toggle("压缩位置格式", isOn: $draft.useCompression)
                TextField("位置注释", text: $draft.comment)
            }

            Section {
                Toggle("已确认持照与责任声明", isOn: $draft.licenseConfirmed)
            } footer: {
                Text("首次连接 APRS-IS 前必须确认：你持有有效业余无线电执照，并对注入的报文负责。")
            }

            Section {
                Button("保存") {
                    store.config = draft
                    dismiss()
                }
            }
        }
        .navigationTitle("APRS 设置")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { draft = store.config }
    }
}

/// 站点列表：对应 Android `AprsStations`。
struct AprsStationsView: View {

    @ObservedObject var store = AprsStore.shared

    var body: some View {
        List {
            if store.stations.isEmpty {
                Text("还没有收到站点；连接 APRS-IS 并等待位置包。")
                    .foregroundStyle(.secondary)
            }
            ForEach(store.stations) { station in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(station.callsign).font(.headline)
                        Spacer()
                        Text(String(format: "%.3f, %.3f", station.latitude, station.longitude))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    if !station.comment.isEmpty {
                        Text(station.comment).font(.caption).foregroundStyle(.secondary)
                    }
                    HStack(spacing: 12) {
                        if station.altitudeMeters > 0 {
                            Text(String(format: "%.0f m", station.altitudeMeters))
                        }
                        if station.course > 0 || station.speedKnots > 0 {
                            Text("\(station.course)° / \(station.speedKnots) kt")
                        }
                        Spacer()
                        Text(station.lastHeard, style: .time)
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("APRS 站点")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 消息页：对应 Android `AprsMessages`，支持发送与查看 ack/rej 标记。
struct AprsMessagesView: View {

    @ObservedObject var store = AprsStore.shared

    @State private var destination = ""
    @State private var bodyText = ""

    var body: some View {
        List {
            Section("发送") {
                TextField("目标呼号", text: $destination)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                TextField("消息内容", text: $bodyText)
                Button("发送") {
                    store.sendMessage(destination: destination, body: bodyText)
                    bodyText = ""
                }
                .disabled(destination.isEmpty || bodyText.isEmpty || !store.isConnected)
            }

            Section("消息") {
                if store.messages.isEmpty {
                    Text("暂无消息").foregroundStyle(.secondary)
                }
                ForEach(store.messages) { message in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(message.isOutgoing ? "→ \(message.destination)" : "← \(message.source)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(message.timestamp, style: .time)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Text(message.body)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("APRS 消息")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 站点地图：对应 Android `AprsMap`，用 MapKit 标注站点。
///
/// 区域用 `@State` 而不是 `.constant(...)`：后者会让用户每次拖动后立刻弹回
/// 原视野（看起来像「地图拖不动」）。现在默认自动框住所有站点，之后完全交给用户，
/// 右上角「框选全部」可以把视野再拉回来。
struct AprsMapView: View {

    @ObservedObject var store = AprsStore.shared

    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 35, longitude: 105),
        span: MKCoordinateSpan(latitudeDelta: 40, longitudeDelta: 60)
    )
    @State private var didFit = false

    var body: some View {
        Group {
            if store.stations.isEmpty {
                Text("暂无站点可显示").foregroundStyle(.secondary)
            } else {
                Map(coordinateRegion: $region, interactionModes: .all, annotationItems: store.stations) { station in
                    MapAnnotation(coordinate: CLLocationCoordinate2D(
                        latitude: station.latitude,
                        longitude: station.longitude
                    )) {
                        VStack(spacing: 2) {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .font(.caption)
                                .padding(5)
                                .background(Color.accentColor.opacity(0.2))
                                .clipShape(Circle())
                            Text(station.callsign)
                                .font(.caption2)
                                .padding(.horizontal, 4)
                                .background(.thinMaterial)
                                .clipShape(Capsule())
                        }
                    }
                }
                .overlay(alignment: .topTrailing) {
                    Button {
                        fitStations()
                    } label: {
                        Image(systemName: "scope")
                            .font(.system(size: 15, weight: .semibold))
                            .padding(10)
                            .background(.thinMaterial)
                            .clipShape(Circle())
                    }
                    .padding()
                }
                .onAppear {
                    if !didFit { fitStations() }
                }
            }
        }
        .navigationTitle("APRS 地图")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// 把视野框到所有站点（单个站点时给一个合理的最小跨度）。
    private func fitStations() {
        guard !store.stations.isEmpty else { return }
        let lats = store.stations.map { $0.latitude }
        let lons = store.stations.map { $0.longitude }
        let minLat = lats.min() ?? 0, maxLat = lats.max() ?? 0
        let minLon = lons.min() ?? 0, maxLon = lons.max() ?? 0
        region = MKCoordinateRegion(
            center: CLLocationCoordinate2D(
                latitude: (minLat + maxLat) / 2,
                longitude: (minLon + maxLon) / 2
            ),
            span: MKCoordinateSpan(
                latitudeDelta: max(0.5, (maxLat - minLat) * 1.4),
                longitudeDelta: max(0.5, (maxLon - minLon) * 1.4)
            )
        )
        didFit = true
    }
}

/// 符号选择器：对应 Android `AprsSymbolPicker`（常用符号子集）。
struct AprsSymbolPickerView: View {

    @ObservedObject var store = AprsStore.shared
    @Environment(\.dismiss) private var dismiss

    private let symbols: [(table: String, code: String, name: String)] = [
        ("/", ">", "汽车"),
        ("/", "[", "行人"),
        ("/", "b", "自行车"),
        ("/", "j", "吉普"),
        ("/", "k", "卡车"),
        ("/", "u", "巴士"),
        ("/", "-", "房屋"),
        ("/", "_", "气象站"),
        ("/", "#", "数字中继"),
        ("/", "&", "网关"),
        ("\\", "#", "数字中继（备用表）"),
        ("/", "r", "中继"),
        ("/", "s", "卫星"),
        ("/", "S", "航天器"),
        ("/", "Y", "天线"),
    ]

    var body: some View {
        List {
            ForEach(Array(symbols.enumerated()), id: \.offset) { _, item in
                Button {
                    store.config.symbolTable = item.table
                    store.config.symbolCode = item.code
                    dismiss()
                } label: {
                    HStack {
                        Text(item.table + item.code)
                            .font(.body.monospaced())
                        Text(item.name).foregroundStyle(.primary)
                        Spacer()
                        if store.config.symbolTable == item.table && store.config.symbolCode == item.code {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("APRS 符号")
        .navigationBarTitleDisplayMode(.inline)
    }
}
