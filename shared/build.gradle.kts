@file:Suppress("UnstableApiUsage")

// HamKit 跨平台内核（Kotlin Multiplatform）
//
// 这里只放**与平台无关**的领域逻辑：分区定位、Maidenhead、SGP4/SDP4 轨道传播、
// 过境计算等。Android 与 iOS（以及后续桌面端）共用同一份实现，避免两套算法漂移。
//
// 目录约定：
//   src/commonMain/kotlin —— 纯 Kotlin，禁止出现 android.* / java.* / androidx.*
//   src/androidMain/kotlin —— 仅 Android 需要的 actual 实现
//   src/iosMain/kotlin     —— iOS / Kotlin-Native 的 actual 实现
//   src/jvmMain/kotlin     —— 桌面 JVM 的 actual 实现（同时方便在非 macOS 主机上跑纯 JVM 校验）
//
// 注意：iOS 目标只能在 macOS 主机上链接（Kotlin/Native 的 Apple 平台限制）。
// 在 Windows/Linux 上声明这些目标是安全的，但不要调用 *IosArm64 相关任务。

val androidCompileSdkVersion: Int by rootProject.extra
val androidCompileSdkVersionMinor: Int by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    // Android 侧：作为普通 Android Library 被 :app 依赖
    androidLibrary {
        namespace = "com.example.hamkit.shared"
        compileSdk = androidCompileSdkVersion
        minSdk = androidMinSdkVersion
    }

    // 桌面 JVM：便于在 CI / Windows 上直接编译校验 commonMain，无需 macOS
    jvm()

    // Apple 平台：iOS 真机 + Apple Silicon 模拟器
    iosArm64()
    iosSimulatorArm64()

    // iOS 侧统一导出一个名为 Shared 的静态 framework，Swift 端 import Shared 即可
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        // 内核的协议解析 / SSE 分帧 / 工具注册都需要单测锁定行为：
        // 这些代码在 iOS 上无法断点调试，只能靠 JVM 侧的单测保证不回归。
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// ── 离线构建兜底 ────────────────────────────────────────────────────────────
// KMP 会给每个编译配置挂上 Kotlin 脚本编译器插件（kotlin-scripting-compiler-embeddable），
// 用于编译 source set 里的 .kts 脚本。本模块没有任何脚本源，正常联网构建时该依赖由
// Maven Central 提供；完全离线、本地缓存又缺这个 jar 时，可以用
//   ./gradlew -PhamkitOfflineBuild=true ...
// 把它排除掉，从而在没有网络的机器上也能验证 commonMain 是否能编译通过。
if (providers.gradleProperty("hamkitOfflineBuild").isPresent) {
    configurations.configureEach {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-compiler-embeddable")
    }
}
