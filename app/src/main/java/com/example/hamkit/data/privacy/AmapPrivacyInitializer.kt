package com.example.hamkit.data.privacy

import android.content.Context
import android.util.Log
import com.amap.api.maps.MapsInitializer

/**
 * 高德 SDK 的隐私合规初始化。
 *
 * 高德要求：**先取得用户对隐私政策的同意，再调用**
 * [MapsInitializer.updatePrivacyShow] / [MapsInitializer.updatePrivacyAgree]，
 * 之后才能调用任何地图/天气/逆地理编码接口。
 *
 * 因此本类不能在 `Application.onCreate()` 里无条件执行，只能：
 * 1. 用户已完成首启同意后；
 * 2. 或冷启动时检测到历史同意仍然有效。
 */
object AmapPrivacyInitializer {

    private const val TAG = "AmapPrivacy"

    @Volatile
    private var initialized = false

    /**
     * 幂等初始化。未同意隐私政策时直接返回，不触碰任何高德接口。
     */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        if (!PrivacyConsentStore.isAccepted(context)) {
            Log.i(TAG, "privacy consent not accepted; skip Amap SDK init")
            return
        }

        val app = context.applicationContext
        try {
            MapsInitializer.updatePrivacyShow(app, true, true)
            MapsInitializer.updatePrivacyAgree(app, true)
        } catch (t: Throwable) {
            // 高德 SDK 初始化失败不应阻塞应用启动；地图功能会自行报错。
            Log.e(TAG, "updatePrivacyShow/Agree failed", t)
            return
        }

        // 地图 SDK Key 由构建时 manifestPlaceholders 注入（AMAP_SDK_KEY）。
        // 它是客户端标识、不是秘密；高德侧应按包名 + 签名 SHA-1 限制，防止被其他应用直接复用。
        initialized = true
    }
}
