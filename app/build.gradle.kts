@file:Suppress("UnstableApiUsage")

import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ══════════════════════════════════════════════════════════════════════════
// 敏感信息加密方案：三碎片密钥 + AES-256-GCM（详见 docs/CRYPTO_DESIGN.md）
//
// 主密钥 = ShardA ⊕ ShardB ⊕ ShardC（均在下方定义）
// ShardA → BuildConfig.CRYPTO_SHARD_A（Base64）
// ShardB → SecretManager.kt 中混淆存储
// ShardC → SHA-256(包名 + SALT) 运行时计算
// ══════════════════════════════════════════════════════════════════════════

// ShardA（32 字节）：编译期常量，注入 BuildConfig
val CRYPTO_SHARD_A_BYTES = byteArrayOf(
    0x5C.toByte(), 0x3A.toByte(), 0x91.toByte(), 0xF7.toByte(),
    0x2E.toByte(), 0xD4.toByte(), 0x08.toByte(), 0xB6.toByte(),
    0x7F.toByte(), 0x1C.toByte(), 0xA3.toByte(), 0x55.toByte(),
    0xE9.toByte(), 0x0D.toByte(), 0x42.toByte(), 0x8B.toByte(),
    0xC6.toByte(), 0x70.toByte(), 0x3F.toByte(), 0x1A.toByte(),
    0x9E.toByte(), 0xB2.toByte(), 0x57.toByte(), 0xE4.toByte(),
    0x21.toByte(), 0x8D.toByte(), 0xF0.toByte(), 0x63.toByte(),
    0x4A.toByte(), 0x19.toByte(), 0xD5.toByte(), 0x7E.toByte()
)
// ShardA 的 Base64 编码，写入 BuildConfig
val CRYPTO_SHARD_A = Base64.getEncoder().encodeToString(CRYPTO_SHARD_A_BYTES)

// ShardB 原始值（32 字节）：与 SecretManager.kt 中 SHARD_B_OBF ⊕ SHARD_B_KEY 结果一致
val SHARD_B_RAW = byteArrayOf(
    0x3F.toByte(), 0xC8.toByte(), 0x52.toByte(), 0xA1.toByte(),
    0x9D.toByte(), 0xE0.toByte(), 0x44.toByte(), 0x2C.toByte(),
    0xF1.toByte(), 0x68.toByte(), 0xB7.toByte(), 0x3D.toByte(),
    0x0A.toByte(), 0x9E.toByte(), 0x51.toByte(), 0x73.toByte(),
    0xD4.toByte(), 0x29.toByte(), 0x8F.toByte(), 0x66.toByte(),
    0x1B.toByte(), 0xC3.toByte(), 0x5A.toByte(), 0xE7.toByte(),
    0x48.toByte(), 0x07.toByte(), 0x92.toByte(), 0xBE.toByte(),
    0xD6.toByte(), 0x30.toByte(), 0x1C.toByte(), 0xF4.toByte()
)

// ShardC 派生参数（与 SecretManager.kt 一致）
val SHARD_C_SALT = "R4d10_Ar34_L0c8t0r_2026_Salt"
val APP_PACKAGE_NAME = "com.example.hamkit"

/**
 * 组装 256 位主密钥：ShardA ⊕ ShardB ⊕ ShardC。
 * 与 SecretManager.kt 中的 assembleMasterKey() 逻辑完全一致。
 */
fun assembleMasterKey(): ByteArray {
    val shardA = CRYPTO_SHARD_A_BYTES
    val shardB = SHARD_B_RAW
    val shardC = MessageDigest.getInstance("SHA-256")
        .digest((APP_PACKAGE_NAME + SHARD_C_SALT).toByteArray())
        .copyOfRange(0, 32)
    return xorBytes(xorBytes(shardA, shardB), shardC)
}

fun xorBytes(a: ByteArray, b: ByteArray): ByteArray =
    ByteArray(a.size) { i -> (a[i].toInt() xor b[i % b.size].toInt()).toByte() }

/** 转义 JSON 字符串中的特殊字符，防止注入。 */
fun escapeJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

/**
 * AES-256-GCM 加密明文，输出 secrets.dat 二进制格式：
 * [4B magic "SCL1"] [4B version] [12B IV] [NB 密文+认证标签]
 */
fun encryptSecretsData(plaintext: String): ByteArray {
    val masterKey = assembleMasterKey()
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, iv))
    val cipherText = cipher.doFinal(plaintext.toByteArray())
    val result = ByteArray(8 + 12 + cipherText.size)
    System.arraycopy("SCL1".toByteArray(), 0, result, 0, 4)       // magic
    result[4] = 0; result[5] = 0; result[6] = 0; result[7] = 1    // version = 1
    System.arraycopy(iv, 0, result, 8, 12)                        // IV
    System.arraycopy(cipherText, 0, result, 20, cipherText.size)  // ciphertext
    return result
}

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.lsplugin.apksign)
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

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

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

        // 从 local.properties 读取明文密钥（仅开发环境需要，CI 不依赖）
        val localProps = Properties().apply {
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { load(it) }
            }
        }

        // ShardA 注入 BuildConfig（Base64 编码），运行时由 SecretManager 解码
        buildConfigField("String", "CRYPTO_SHARD_A", "\"$CRYPTO_SHARD_A\"")

        // SDK Key 通过 manifestPlaceholders 注入 AndroidManifest meta-data。
        // 本地构建从 local.properties 读取；CI 构建由 SecretManager 运行时设置。
        manifestPlaceholders["AMAP_SDK_KEY"] = localProps.getProperty("amap.sdk.key", "")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "signing/release.keystore"
            val keystoreFile = file(keystorePath)
            val envStorePassword = System.getenv("KEYSTORE_PASSWORD")
            val envKeyAlias = System.getenv("KEY_ALIAS")
            val envKeyPassword = System.getenv("KEY_PASSWORD")

            if (keystoreFile.exists() &&
                envStorePassword != null &&
                envKeyAlias != null &&
                envKeyPassword != null
            ) {
                // CI release：使用真正的 release keystore（由 release.yml 注入 secrets）
                storeFile = keystoreFile
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            } else {
                // 本地打包回退到 debug keystore，保证签名稳定且可重复
                // ~/.android/debug.keystore 由 Android SDK 维护，签名指纹在不同构建间保持一致
                storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
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

// ══════════════════════════════════════════════════════════════════════════
// Gradle Task: encryptSecrets
//
// 开发阶段使用：从 local.properties 读取明文密钥，加密后写入
// app/src/main/assets/secrets.dat。开发者将 secrets.dat 提交到 git，
// CI 构建不再需要任何 GitHub Secrets。
//
// 用法：gradle encryptSecrets
// ══════════════════════════════════════════════════════════════════════════
tasks.register("encryptSecrets") {
    doLast {
        val localProps = Properties().apply {
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { load(it) }
            }
        }

        val apiKey = localProps.getProperty("amap.api.key", "")
        val sdkKey = localProps.getProperty("amap.sdk.key", "")
        if (apiKey.isEmpty() && sdkKey.isEmpty()) {
            logger.warn("Warning: local.properties 中未找到 amap.api.key / amap.sdk.key，跳过加密。")
            return@doLast
        }

        // 构建明文 JSON（对特殊字符转义，防止注入）
        val json = StringBuilder("{")
        var first = true
        if (apiKey.isNotEmpty()) {
            json.append("\"amap.api.key\":\"${escapeJson(apiKey)}\"")
            first = false
        }
        if (sdkKey.isNotEmpty()) {
            if (!first) json.append(",")
            json.append("\"amap.sdk.key\":\"${escapeJson(sdkKey)}\"")
        }
        json.append("}")

        val encrypted = encryptSecretsData(json.toString())
        val assetsDir = file("src/main/assets")
        assetsDir.mkdirs()
        val secretsFile = file("src/main/assets/secrets.dat")
        secretsFile.writeBytes(encrypted)

        println("secrets.dat generated: ${secretsFile.absolutePath} (${encrypted.size} bytes)")
    }
}
