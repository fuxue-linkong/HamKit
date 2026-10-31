import SwiftUI
import Shared

/// CW 练习首页：对应 Android `CWPracticeScreen` —— 自由练习 / 教程练习 / 摩斯电码编解码。
struct CwPracticeHomeView: View {

    var body: some View {
        List {
            Section {
                NavigationLink(value: Route.cwFreePractice) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("自由练习")
                            Text("自选字符集与长度，听抄后对照答案")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "waveform")
                    }
                }
                NavigationLink(value: Route.cwTutorial) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("教程练习")
                            Text("Koch 课程 / 字符组 / 呼号 / 文本")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "book")
                    }
                }
                NavigationLink(value: Route.cwMorseCodec) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("摩斯电码编解码")
                            Text("文本 ↔ 摩斯码互转")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "textformat.123")
                    }
                }
            } header: {
                Text("CW")
            } footer: {
                Text("音频用 AVAudioEngine 实时合成；节拍与 Android 端共用共享内核的时间线模型。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("CW 练习")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 自由练习设置 + 播放 + 听抄对照。
struct CwPracticeView: View {

    @ObservedObject var settings = CwSettingsStore.shared
    @StateObject private var player = MorsePlayer()

    @State private var text = ""
    @State private var input = ""
    @State private var result: String?
    @State private var generated = false

    var body: some View {
        Form {
            Section("设置") {
                Stepper("速度 \(settings.wpm) WPM", value: $settings.wpm, in: 5...40)
                Stepper("音调 \(settings.toneHz) Hz", value: $settings.toneHz, in: 300...1200, step: 25)
                Picker("字符集", selection: $settings.characterSet) {
                    Text("字母").tag("letters")
                    Text("数字").tag("numbers")
                    Text("符号").tag("symbols")
                    Text("字母+数字").tag("custom")
                }
            }

            Section("练习文本") {
                if generated {
                    Text(text)
                        .font(.system(.title3, design: .monospaced))
                        .textSelection(.enabled)
                } else {
                    Text("点「生成练习」开始").foregroundStyle(.secondary)
                }
                HStack {
                    Button("生成练习") { generate() }
                    Spacer()
                    Button(player.isPlaying ? "停止" : "播放") {
                        if player.isPlaying {
                            player.stop()
                        } else {
                            play()
                        }
                    }
                    .disabled(text.isEmpty)
                }
            }

            Section("听抄") {
                TextField("输入你听到的内容", text: $input)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Button("对照答案") { check() }
                    .disabled(text.isEmpty || input.isEmpty)
                if let result {
                    Text(result)
                        .font(.footnote)
                        .foregroundStyle(result.contains("全对") ? Color.green : Color.orange)
                }
            }
        }
        .navigationTitle("自由练习")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { player.stop() }
    }

    private func generate() {
        text = MorseCode.shared.generateRandomCharacters(
            characterSet: settings.characterSet,
            length: 5
        )
        // 用 5 组字符拼出更接近 Android 的练习长度（每组 5 个）
        let groups = (1...5).map { _ in
            MorseCode.shared.generateRandomCharacters(characterSet: settings.characterSet, length: 5)
        }
        text = groups.joined(separator: " ")
        input = ""
        result = nil
        generated = true
    }

    private func play() {
        player.play(
            morse: MorseCode.shared.toMorse(text: text),
            wpm: settings.wpm,
            frequency: Double(settings.toneHz)
        )
    }

    private func check() {
        let expected = text.uppercased().filter { !$0.isWhitespace }
        let actual = input.uppercased().filter { !$0.isWhitespace }
        if expected == actual {
            result = "全对 \(expected.count) 个字符"
        } else {
            let correct = zip(expected, actual).filter { $0 == $1 }.count
            result = "正确 \(correct)/\(expected.count) 个字符\n答案：\(text)"
        }
    }
}

/// 教程练习：4 类课程，Koch 按课推进，进度存 UserDefaults。
struct CwTutorialView: View {

    @ObservedObject var settings = CwSettingsStore.shared
    @StateObject private var player = MorsePlayer()

    private let courses: [(id: Int, title: String, detail: String, lessons: Int)] = [
        (1, "Koch 课程", "每课增加 1 个字符，从 K/M 开始", 26),
        (2, "字符组", "3 字符一组，练习节奏与间隔", 10),
        (3, "呼号训练", "随机中国呼号", 10),
        (4, "文本训练", "CQ / 通联常用句型", 10),
    ]

    var body: some View {
        List {
            Section("设置") {
                Stepper("速度 \(settings.wpm) WPM", value: $settings.wpm, in: 5...40)
                Stepper("音调 \(settings.toneHz) Hz", value: $settings.toneHz, in: 300...1200, step: 25)
            }

            ForEach(courses, id: \.id) { course in
                Section(course.title) {
                    Text(course.detail)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ForEach(1...course.lessons, id: \.self) { lesson in
                        NavigationLink {
                            CwLessonView(courseId: course.id, lesson: lesson, player: player)
                        } label: {
                            HStack {
                                Text("第 \(lesson) 课")
                                Spacer()
                                if course.id == 1 {
                                    Text(MorseCode.shared.kochLessonChars(lesson: Int32(lesson)))
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("教程练习")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { player.stop() }
    }
}

/// 单课练习：生成该课文本、播放、听抄、看答案。
struct CwLessonView: View {

    @ObservedObject var settings = CwSettingsStore.shared
    let courseId: Int
    let lesson: Int
    @ObservedObject var player: MorsePlayer

    @State private var text = ""
    @State private var input = ""
    @State private var showAnswer = false

    var body: some View {
        Form {
            Section("练习文本") {
                Text(text)
                    .font(.system(.title3, design: .monospaced))
                    .textSelection(.enabled)
                Button(player.isPlaying ? "停止" : "播放") {
                    if player.isPlaying {
                        player.stop()
                    } else {
                        player.play(
                            morse: MorseCode.shared.toMorse(text: text),
                            wpm: settings.wpm,
                            frequency: Double(settings.toneHz)
                        )
                    }
                }
            }

            Section("听抄") {
                TextField("输入你听到的内容", text: $input)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Toggle("显示答案", isOn: $showAnswer)
                if showAnswer {
                    Text(text).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("第 \(lesson) 课")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            text = MorseCode.shared.tutorialContent(
                courseId: Int32(courseId),
                lessonId: Int32(lesson),
                length: 25
            )
        }
        .onDisappear { player.stop() }
    }
}

/// 摩斯电码编解码页：对应 Android `MorseCodeScreen`。
struct CwMorseCodecView: View {

    @State private var plain = ""
    @State private var morse = ""
    @State private var message: String?

    var body: some View {
        Form {
            Section("文本") {
                TextEditor(text: $plain)
                    .frame(minHeight: 100)
                    .textInputAutocapitalization(.never)
                Button("编码为摩斯码") {
                    morse = MorseCode.shared.encode(text: plain)
                    message = nil
                }
                .disabled(plain.isEmpty)
            }

            Section("摩斯码") {
                TextEditor(text: $morse)
                    .font(.body.monospaced())
                    .frame(minHeight: 100)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("解码为文本") {
                    plain = MorseCode.shared.decode(morse: morse)
                    message = nil
                }
                .disabled(morse.isEmpty)
            }

            if let message {
                Text(message).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("摩斯电码")
        .navigationBarTitleDisplayMode(.inline)
    }
}
