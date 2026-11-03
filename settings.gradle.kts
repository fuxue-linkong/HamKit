pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 高德地图 SDK Maven 仓库（国内镜像，稳定可靠）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // JitPack（MPAndroidChart、predict4java 等 GitHub 开源库）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "HamKit"
include(":app")
// 跨平台内核（Kotlin Multiplatform）：Android / iOS / 桌面 JVM 共用
include(":shared")
