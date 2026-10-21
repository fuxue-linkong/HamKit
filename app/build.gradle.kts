@file:Suppress("UnstableApiUsage")

import org.gradle.api.GradleException
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import java.util.Properties

// 说明：地图 SDK 与 Web 服务（天气/逆地理编码）都使用高德。
// 两个 Key 都随 APK 分发，属于客户端标识而非秘密；不再使用 secrets.dat/三碎片方案。
// SDK Key 按包名 + 签名 SHA-1 限制；Web Key 只能靠配额/用量监控。

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    jacoco
}

val androidCompileSdkVersion: Int by rootProject.extra
val androidCompileSdkVersionMinor: Int by rootProject.extra
val androidBuildToolsVersion: String by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra
val androidTargetSdkVersion: Int by rootProject.extra
val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra
val managerVersionCode: Int by rootProject.extra
val managerVersionName: String by rootProject.extra

// ── Release 签名：只认环境变量，不再静默回退 debug key ──────────────────────
// debug keystore 的私钥是公开的，任何拿到它的人都能签出“同包名同签名”的 APK。
// 缺少 release keystore/密码时，release 构建必须失败，而不是产出可被伪造的包。
val releaseKeystoreFile = file(System.getenv("KEYSTORE_PATH") ?: "signing/release.keystore")
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val hasReleaseSigning = releaseKeystoreFile.isFile &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.example.hamkit"
    compileSdk {
        version = release(androidCompileSdkVersion) {
            minorApiLevel = androidCompileSdkVersionMinor
        }
    }
    buildToolsVersion = androidBuildToolsVersion

    defaultConfig {
        applicationId = "com.example.hamkit"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 高德 Android SDK Key：本地从 local.properties 读取，CI 用 AMAP_SDK_KEY 环境变量。
        // 它随 APK 分发，必须按包名 + 签名 SHA-1 在高德控制台限制；不要把它当秘密。
        val localProps = Properties().apply {
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { load(it) }
            }
        }
        val amapSdkKey = System.getenv("AMAP_SDK_KEY")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("amap.sdk.key", "")
        manifestPlaceholders["AMAP_SDK_KEY"] = amapSdkKey

        // 高德 Web 服务 Key（天气/逆地理编码）：本地从 local.properties 读取，
        // CI 用 AMAP_WEB_KEY。它同样随 APK 分发，可按包名/SHA-1 限制 SDK Key，
        // 但 Web Key 无法绑定 Android 包名，只能靠配额/用量监控。
        val amapWebKey = System.getenv("AMAP_WEB_KEY")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("amap.api.key", "")
        val escapedAmapWebKey = amapWebKey
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "AMAP_WEB_KEY", "\"$escapedAmapWebKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            // 未配置 release 签名时为 null；taskGraph.whenReady 会在真正打包前失败。
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // lint 配置：禁用 InvalidFragmentVersionForActivityResult 误报
    //（androidx.activity-compose 已包含 Fragment 1.3.0+，lint 未正确识别）
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
        abortOnError = false
        checkReleaseBuilds = false
    }

    // 单元测试配置：并行 fork + JVM 调优 + JaCoCo 覆盖率
    testOptions {
        unitTests {
            all {
                // 并行 fork：利用多核 CPU，每个 fork 运行一个测试类。
                // 上限 4：持续接收 / 融合用例各自持有较大的频率缓冲（单帧解码音频可达
                // 数千万样本），fork 数随核数线性增长时，GitHub 的 4 核 runner 上
                // 并行峰值会把容器内存打满，JVM 直接失败退出（TEST-*.xml 一个都不生成，
                // 表现为「单元测试门禁空跑」，极难定位）。4 个分片已能跑满整轮。
                it.maxParallelForks =
                    (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
                // 每个 fork 的 JVM 堆内存（避免 OOM，也避免过大导致 GC 停顿）
                it.setJvmArgs(
                    listOf(
                        "-Xmx2048m",
                        "-XX:+UseG1GC",
                        "-XX:MaxGCPauseMillis=200",
                    )
                )
                // 每个 fork 运行 N 个测试类后回收（防止内存泄漏累积）
                it.forkEvery = 100
                it.extensions.configure<JacocoTaskExtension> {
                    isIncludeNoLocationClasses = true
                    excludes = listOf(
                        "jdk.internal.*",
                        "android.*",
                        "androidx.*",
                        "com.android.*",
                        "*_Factory",
                        "*_MembersInjector",
                        "*Hilt*",
                        "Dagger*",
                        "*Binding*"
                    )
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    androidResources {
        generateLocaleConfig = true
    }
}

// 在任何 release 产物任务开始前检查签名配置，避免产出 debug 签名/无签名的 release 包。
gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { task ->
        task.name == "assembleRelease" ||
            task.name == "bundleRelease" ||
            task.name == "installRelease" ||
            task.name.startsWith("packageRelease")
    }
    if (releaseRequested && !hasReleaseSigning) {
        throw GradleException(
            "Release signing is not configured. Set KEYSTORE_PATH / KEYSTORE_PASSWORD / " +
                "KEY_ALIAS / KEY_PASSWORD (or use scripts/release.ps1). " +
                "Refusing to build a release APK that could fall back to the public debug key."
        )
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.addAll(listOf("META-INF/**", "kotlin/**", "**.bin"))
    }
}

base {
    archivesName.set("HamKit_${managerVersionName}_${managerVersionCode}")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

// JaCoCo 工具版本配置（顶层 Gradle Jacoco 插件扩展）
jacoco {
    toolVersion = "0.8.12"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.palette)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigationevent.compose)

    // Markdown 渲染
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    // Miuix — Compose Multiplatform MIUI-style UI library（全套）
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.squircle)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.hiddenapibypass)
    implementation(libs.material.kolor)

    // predict4java 仅用于差分测试：验证自研 SGP4/SDP4 引擎（data/satellite/predict）的
    // 输出与其一致性。JVM 下 commons-logging 默认实现可用（无需 Android SPI hack）。
    testImplementation(libs.predict4java)
    testImplementation(libs.commons.logging)

    // Image loading (GitHub avatars in About)
    implementation(libs.coil.compose)

    // Image cropping for custom time card background
    implementation("com.github.CanHub:Android-Image-Cropper:4.5.0")

    // 高德地图 SDK：3D地图（含缩放手势、Marker、控件）
    // 文档：https://lbs.amap.com/api/android-sdk/summary
    implementation(libs.amap.map3d)

    // WorkManager: 用于日程提醒的每日刷新周期任务
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore: 用于 CW 设置的持久化存储
    implementation(libs.androidx.datastore.preferences)

    // Room 数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 图表库
    implementation(libs.mpandroidchart)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}


