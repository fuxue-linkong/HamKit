package com.example.hamkit.shared

/**
 * HamKit 跨平台内核的版本标识。
 *
 * 保留一个不依赖任何平台 API 的入口，便于 iOS 壳工程在启动时做一次
 * 「framework 是否真正链接进来了」的冒烟检查。
 */
object HamKitCore {
    const val VERSION: String = "1.0.0"
}
