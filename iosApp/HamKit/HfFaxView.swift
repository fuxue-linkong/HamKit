import SwiftUI
import UIKit
import Shared

/// HF Fax（WEFAX）接收页：对应 Android `HfFax`。
///
/// 录音 → 共享内核 `HfFaxBridge` 解调/解码 → 灰度图展示 → 保存到相册。
struct HfFaxView: View {

    @StateObject private var audio = SstvAudio()
    @State private var startImmediately = false
    @State private var status = "空闲"
    @State private var image: UIImage?
    @State private var info = ""

    var body: some View {
        List {
            Section("接收") {
                Toggle("跳过 300Hz 启动音（手动对齐）", isOn: $startImmediately)
                Button(audio.isRecording ? "停止并解码" : "开始接收") {
                    if audio.isRecording {
                        let data = audio.stop()
                        status = "解码中…"
                        decode(data: data)
                    } else {
                        audio.start()
                        status = "正在接收…"
                    }
                }
                if audio.isRecording {
                    Text(String(format: "已录 %.1f 秒", audio.elapsed))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Text(status).font(.footnote).foregroundStyle(.secondary)
            }

            if let image {
                Section("解码结果") {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    if !info.isEmpty {
                        Text(info).font(.caption).foregroundStyle(.secondary)
                    }
                    Button {
                        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
                        status = "已保存到相册"
                    } label: {
                        Label("保存到相册", systemImage: "square.and.arrow.down")
                    }
                }
            }

            Section {
                Text("WEFAX 常见频率：3.850 / 7.880 / 13.882 MHz（USB），线速率 120 行/分。iOS 端仅前台接收。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("HF 传真")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { audio.cancel() }
    }

    private func decode(data: Data) {
        let skipStartTone = startImmediately
        DispatchQueue.global(qos: .userInitiated).async {
            let bytes = KotlinByteArray(size: Int32(data.count))
            for (index, byte) in data.enumerated() {
                bytes.set(index: Int32(index), value: Int8(bitPattern: byte))
            }
            let outcome = HfFaxBridge.shared.decodePcm(bytes: bytes, startImmediately: skipStartTone)
            DispatchQueue.main.async {
                guard outcome.success else {
                    status = "解码失败：\(outcome.error)"
                    return
                }
                if let decoded = Self.makeImage(
                    pixels: outcome.pixels,
                    width: Int(outcome.width),
                    height: Int(outcome.height)
                ) {
                    image = decoded
                    status = "解码成功"
                    info = "\(outcome.width)×\(outcome.height) · \(outcome.aligned ? "已对齐" : "未对齐") · 平移 \(outcome.shiftPixels) px"
                } else {
                    status = "生成图像失败"
                }
            }
        }
    }

    private static func makeImage(pixels: KotlinIntArray, width: Int, height: Int) -> UIImage? {
        guard width > 0, height > 0 else { return nil }
        var rgba = [UInt8](repeating: 255, count: width * height * 4)
        for i in 0..<(width * height) {
            let rgb = pixels.get(index: Int32(i))
            rgba[i * 4] = UInt8((rgb >> 16) & 0xFF)
            rgba[i * 4 + 1] = UInt8((rgb >> 8) & 0xFF)
            rgba[i * 4 + 2] = UInt8(rgb & 0xFF)
            rgba[i * 4 + 3] = 255
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let cgImage = CGImage(
                width: width,
                height: height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
              ) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
