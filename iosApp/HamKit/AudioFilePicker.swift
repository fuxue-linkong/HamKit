import SwiftUI
import UniformTypeIdentifiers

/// 音频文件选择器：`UIDocumentPickerViewController` + `asCopy: true`。
///
/// **为什么不用 SwiftUI 的 `.fileImporter`**（它有三个已知的「点了没反应」路径）：
///
/// 1. **真机上点文件不回调**。`.fileImporter` 在部分真机 / 新系统上会弹出选择器却
///    不响应选择：文件点下去既不关闭、也不触发回调，只能杀掉 App；同一台机器上
///    UIKit 的选择器正常。换成这层 `UIViewControllerRepresentable` 即可绕开；
/// 2. **回调失败被静默吞掉**。`.fileImporter` 给出 `.failure` 时（云端文件取不下来、
///    提供方出错等），旧代码直接 `guard ... else { return }`，界面上什么都不变，
///    同样表现为「点了没反应」。这里统一把错误交给调用方展示；
/// 3. **云端文件打不开**。`asCopy: true` 会先让系统把选中文件复制到 App 沙盒的临时
///    目录，调用方拿到的是普通本地文件：不需要 security-scoped URL，也不会因为
///    「文件还在 iCloud 里没下载完」而失败 —— 语音备忘录导出的 m4a 经常是这种情况；
///
/// 另外把类型列表放宽到 m4a / MPEG-4：语音备忘录录的是 AAC / m4a，`.audio` 理论上
/// 覆盖它，但部分文件提供方把 m4a 标成 `public.mpeg-4`（视频），只写 `.audio` 时
/// 文件在选择器里是灰的、点不动。
///
/// 回调里的 URL 是**沙盒内的副本**，调用方用完应当删除（见 `SstvStore.decodeFile`）。
struct AudioFilePicker: UIViewControllerRepresentable {

    /// 允许选择的类型。
    ///
    /// `.audio` 已覆盖 wav/mp3/m4a，但仍显式列出常见音频类型与 m4a：
    /// 选择器是按文件提供方上报的 UTType 判断灰/亮，多列几个父类型不改变可选范围，
    /// 却能救回被标错的 m4a。
    static let contentTypes: [UTType] = {
        var types: [UTType] = [.audio, .mpeg4Audio, .mp3, .wav, .aiff]
        if let m4a = UTType(filenameExtension: "m4a") {
            types.append(m4a)
        }
        // 有的提供方把 m4a 当 MPEG-4 视频类型（public.mpeg-4）上报，不列出来就是灰的点不动
        types.append(.mpeg4Movie)
        return types
    }()

    var onPick: (Result<URL, Error>) -> Void
    /// 用户点了「取消」：调用方据此关掉宿主 sheet（否则 sheet 会停在选择器上）。
    var onCancel: () -> Void = {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: Self.contentTypes,
            asCopy: true
        )
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {

        private let onPick: (Result<URL, Error>) -> Void
        private let onCancel: () -> Void

        init(onPick: @escaping (Result<URL, Error>) -> Void, onCancel: @escaping () -> Void) {
            self.onPick = onPick
            self.onCancel = onCancel
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            guard let url = urls.first else {
                onPick(.failure(AudioFilePickerError.noFile))
                return
            }
            onPick(.success(url))
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onCancel()
        }
    }
}

/// 选择器自身的问题（目前只有「回调里没有 URL」一种）。
enum AudioFilePickerError: LocalizedError {
    case noFile

    var errorDescription: String? {
        switch self {
        case .noFile: return "没有取到所选文件"
        }
    }
}
