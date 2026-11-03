import SwiftUI
import MapKit
import Shared

/// 轨道地图页：对应 Android SatelliteMap。
/// 苹果原生 MapKit 实现（不依赖高德 iOS SDK——那需要按 Bundle ID 重新申请 Key）：
/// - 地面轨迹折线（前后各 45 分钟，来自共享内核 satelliteTrack）
/// - 当前星下点标注（卫星图标 + 名称）
/// - 星下点坐标/覆盖半径浮层 + 「回到卫星」按钮
///
/// **手势**：整页只有一个真正的 `MKMapView`，拖动/双指缩放/旋转都是原生行为。
/// 之前用的是 SwiftUI `Map(coordinateRegion: .constant(...))` 再叠一个透明
/// `MKMapView` 画折线——两处都会吃掉手势：`.constant` 让拖动后立刻弹回，
/// 叠加层则挡住了底下的地图。现在改成单个 `MKMapView`，折线用 delegate 渲染。
struct SatelliteMapView: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    /// 每次点「回到卫星」+1，作为重新居中的信号
    @State private var recenterToken = 0

    /// 轨迹分段：经差超过 180° 的相邻点断开（日期变更线），避免横穿地图的直线
    private var trackSegments: [[CLLocationCoordinate2D]] {
        var segments: [[CLLocationCoordinate2D]] = []
        var current: [CLLocationCoordinate2D] = []
        var lastLon: Double?
        for point in model.track {
            let lon = point.subLongitudeDeg
            if let last = lastLon, abs(lon - last) > 180 {
                segments.append(current)
                current = []
            }
            current.append(CLLocationCoordinate2D(latitude: point.subLatitudeDeg, longitude: lon))
            lastLon = lon
        }
        if !current.isEmpty { segments.append(current) }
        return segments
    }

    private var subpoint: CLLocationCoordinate2D? {
        guard model.selectedNorad == norad, let pos = model.position else { return nil }
        return CLLocationCoordinate2D(latitude: pos.subLatitudeDeg, longitude: pos.subLongitudeDeg)
    }

    /// 0° 仰角可见半径（km）：acos(Re / (Re + h)) × Re，简化用星下点高度。
    private var footprintKm: Double {
        guard let pos = model.position else { return 0 }
        let earthRadiusKm = 6371.0
        let h = max(pos.altitudeKm, 1)
        return acos(earthRadiusKm / (earthRadiusKm + h)) * earthRadiusKm
    }

    var body: some View {
        Group {
            if let sub = subpoint {
                ZStack(alignment: .bottomLeading) {
                    SatelliteMapCanvas(
                        segments: trackSegments,
                        subpoint: sub,
                        name: model.selectedSatelliteName,
                        recenterToken: recenterToken
                    )
                    .ignoresSafeArea(edges: .bottom)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(String(format: "星下点 %.2f°, %.2f°", sub.latitude, sub.longitude))
                            .font(.caption.monospacedDigit())
                        Text(String(format: "覆盖半径约 %.0f km", footprintKm))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    .padding(10)
                    .background(.thinMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .padding()
                }
                .overlay(alignment: .topTrailing) {
                    Button {
                        recenterToken += 1
                    } label: {
                        Label("回到卫星", systemImage: "location.fill")
                            .labelStyle(.iconOnly)
                            .font(.system(size: 15, weight: .semibold))
                            .padding(10)
                            .background(.thinMaterial)
                            .clipShape(Circle())
                    }
                    .padding()
                }
            } else {
                ContentUnavailableCompatView(
                    title: "等待位置解算",
                    detail: "TLE 加载并选中该卫星后，这里显示地面轨迹与覆盖范围。"
                )
            }
        }
        .navigationTitle("轨道地图")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if model.selectedNorad != norad {
                model.selectSatellite(norad: norad)
            }
        }
    }
}

/// 单个 `MKMapView` 包一层：原生手势 + 折线 + 星下点标注。
private struct SatelliteMapCanvas: UIViewRepresentable {

    let segments: [[CLLocationCoordinate2D]]
    let subpoint: CLLocationCoordinate2D
    let name: String
    let recenterToken: Int

    /// 世界视野：轨迹横跨日期变更线也能一眼看全
    private static let worldRegion = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 20, longitude: 0),
        span: MKCoordinateSpan(latitudeDelta: 150, longitudeDelta: 360)
    )

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView(frame: .zero)
        map.delegate = context.coordinator
        map.isPitchEnabled = false
        map.isRotateEnabled = true
        map.isZoomEnabled = true
        map.isScrollEnabled = true
        map.showsCompass = true
        map.pointOfInterestFilter = .excludingAll
        map.setRegion(Self.worldRegion, animated: false)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.update(
            map: map,
            segments: segments,
            subpoint: subpoint,
            name: name,
            recenterToken: recenterToken
        )
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MKMapViewDelegate {

        private var polylines: [MKPolyline] = []
        private var lastSegmentCount = -1
        private var lastPointCount = -1
        private var marker: MKPointAnnotation?
        private var lastRecenterToken = -1

        func update(
            map: MKMapView,
            segments: [[CLLocationCoordinate2D]],
            subpoint: CLLocationCoordinate2D,
            name: String,
            recenterToken: Int
        ) {
            let pointCount = segments.reduce(0) { $0 + $1.count }
            if segments.count != lastSegmentCount || pointCount != lastPointCount {
                lastSegmentCount = segments.count
                lastPointCount = pointCount
                map.removeOverlays(polylines)
                polylines = segments
                    .filter { $0.count >= 2 }
                    .map { MKPolyline(coordinates: $0, count: $0.count) }
                map.addOverlays(polylines)
            }

            // 星下点标注：原地更新坐标，避免每秒重建 annotation 导致闪烁
            if let marker {
                marker.coordinate = subpoint
                marker.title = name
            } else {
                let newMarker = MKPointAnnotation()
                newMarker.coordinate = subpoint
                newMarker.title = name
                map.addAnnotation(newMarker)
                marker = newMarker
            }

            // 首次进入或点「回到卫星」时居中；其余时候不动视野，用户拖到哪就是哪
            if lastRecenterToken != recenterToken {
                lastRecenterToken = recenterToken
                let region = MKCoordinateRegion(
                    center: subpoint,
                    span: MKCoordinateSpan(latitudeDelta: 60, longitudeDelta: 80)
                )
                map.setRegion(region, animated: recenterToken != 0)
            }
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let line = overlay as? MKPolyline else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let renderer = MKPolylineRenderer(polyline: line)
            renderer.strokeColor = .systemOrange
            renderer.lineWidth = 3
            renderer.lineCap = .round
            return renderer
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard !(annotation is MKUserLocation) else { return nil }
            let identifier = "subpoint"
            let view = mapView.dequeueReusableAnnotationView(withIdentifier: identifier)
                as? MKMarkerAnnotationView
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: identifier)
            view.annotation = annotation
            view.markerTintColor = .systemBlue
            view.glyphImage = UIImage(systemName: "antenna.radiowaves.left.and.right")
            view.canShowCallout = true
            return view
        }
    }
}

/// 兼容 iOS 16 的「无数据」占位（iOS 17 才有 ContentUnavailableView）。
struct ContentUnavailableCompatView: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "scope")
                .font(.system(size: 36))
                .foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
