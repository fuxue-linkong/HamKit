import SwiftUI
import Shared

/// Look4Sat 风格极坐标雷达：外圈 = 地平线（0°），圆心 = 天顶（90°）。
///
/// - 灰色细圈：30° / 60° / 90° 仰角圈
/// - 蓝色折线：当前卫星的轨迹（前后各 45 分钟，只画地平线以上的部分）
/// - 实心点 + 中心连线：卫星当前位置
///
/// 数据全部来自共享内核（`HamKitApi.satelliteTrack`），Swift 只做投影绘制。
struct RadarView: View {

    let current: PositionSnapshot?
    let track: [PositionSnapshot]

    var body: some View {
        Canvas { context, size in
            let side = min(size.width, size.height)
            guard side > 20 else { return }
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = side / 2 - 14

            drawGrid(context: context, center: center, radius: radius)
            drawTrack(context: context, center: center, radius: radius)
            drawCurrent(context: context, center: center, radius: radius)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel("卫星雷达图")
    }

    // MARK: - 网格

    private func drawGrid(context: GraphicsContext, center: CGPoint, radius: CGFloat) {
        // 仰角圈：0°（地平线）最外圈，90° 收缩成一个点
        for elevation in [0, 30, 60, 90] {
            let ringRadius = radius * (1 - CGFloat(elevation) / 90)
            let rect = CGRect(
                x: center.x - ringRadius,
                y: center.y - ringRadius,
                width: ringRadius * 2,
                height: ringRadius * 2
            )
            context.stroke(
                Path(ellipseIn: rect),
                with: .color(Color.secondary.opacity(elevation == 0 ? 0.45 : 0.2)),
                lineWidth: 1
            )
        }

        var cross = Path()
        cross.move(to: CGPoint(x: center.x - radius, y: center.y))
        cross.addLine(to: CGPoint(x: center.x + radius, y: center.y))
        cross.move(to: CGPoint(x: center.x, y: center.y - radius))
        cross.addLine(to: CGPoint(x: center.x, y: center.y + radius))
        context.stroke(cross, with: .color(Color.secondary.opacity(0.18)), lineWidth: 1)

        let labelOffset = radius + 8
        drawLabel(context, "N", at: CGPoint(x: center.x, y: center.y - labelOffset))
        drawLabel(context, "E", at: CGPoint(x: center.x + labelOffset, y: center.y))
        drawLabel(context, "S", at: CGPoint(x: center.x, y: center.y + labelOffset))
        drawLabel(context, "W", at: CGPoint(x: center.x - labelOffset, y: center.y))
    }

    private func drawLabel(_ context: GraphicsContext, _ text: String, at point: CGPoint) {
        let label = Text(text)
            .font(.system(size: 10, weight: .medium))
            .foregroundColor(.secondary)
        context.draw(label, at: point)
    }

    // MARK: - 轨迹

    private func drawTrack(context: GraphicsContext, center: CGPoint, radius: CGFloat) {
        let visible = track.filter { $0.elevationDeg > 0 }
        guard visible.count > 1 else { return }

        var path = Path()
        var started = false
        for point in visible {
            let position = project(azimuthDeg: point.azimuthDeg, elevationDeg: point.elevationDeg, center: center, radius: radius)
            if started {
                path.addLine(to: position)
            } else {
                path.move(to: position)
                started = true
            }
        }
        context.stroke(
            path,
            with: .color(Color.accentColor.opacity(0.55)),
            style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round)
        )
    }

    // MARK: - 当前位置

    private func drawCurrent(context: GraphicsContext, center: CGPoint, radius: CGFloat) {
        guard let current, current.elevationDeg > 0 else { return }
        let position = project(
            azimuthDeg: current.azimuthDeg,
            elevationDeg: current.elevationDeg,
            center: center,
            radius: radius
        )

        var spoke = Path()
        spoke.move(to: center)
        spoke.addLine(to: position)
        context.stroke(spoke, with: .color(Color.accentColor.opacity(0.45)), lineWidth: 1)

        let dot = CGRect(x: position.x - 4, y: position.y - 4, width: 8, height: 8)
        context.fill(Path(ellipseIn: dot), with: .color(Color.accentColor))
    }

    /// 方位角 0°=北、顺时针；仰角 90°=圆心。
    private func project(
        azimuthDeg: Double,
        elevationDeg: Double,
        center: CGPoint,
        radius: CGFloat
    ) -> CGPoint {
        let r = radius * (1 - CGFloat(max(0, min(90, elevationDeg))) / 90)
        let radians = CGFloat(azimuthDeg) * .pi / 180
        return CGPoint(x: center.x + r * sin(radians), y: center.y - r * cos(radians))
    }
}
