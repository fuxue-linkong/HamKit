package com.example.hamkit.data.privacy

import android.content.Context

/**
 * 本地隐私同意状态。
 *
 * 只在用户明确同意后写入；[CURRENT_VERSION] 与隐私政策版本绑定，政策实质变更时递增，
 * 旧版本同意会自动失效并再次弹出首启同意。
 */
object PrivacyConsentStore {

    /** 与 assets/privacy_policy_*.html 的“版本”保持一致。 */
    const val CURRENT_VERSION = 1

    private const val PREFS_NAME = "privacy_consent"
    private const val KEY_VERSION = "version"
    private const val KEY_POLICY_ACCEPTED = "policy_accepted"
    private const val KEY_LOCATION_ACCEPTED = "location_accepted"
    private const val KEY_APRS_PUBLIC_ACCEPTED = "aprs_public_accepted"
    private const val KEY_ACCEPTED_AT = "accepted_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 隐私政策是否已按当前版本同意。 */
    fun isAccepted(context: Context): Boolean {
        val p = prefs(context)
        return p.getInt(KEY_VERSION, 0) == CURRENT_VERSION &&
            p.getBoolean(KEY_POLICY_ACCEPTED, false)
    }

    /** 是否同意处理精确位置（敏感个人信息）。 */
    fun isLocationAccepted(context: Context): Boolean =
        isAccepted(context) && prefs(context).getBoolean(KEY_LOCATION_ACCEPTED, false)

    /** 是否同意在开启 APRS 上报时公开位置。 */
    fun isAprsPublicAccepted(context: Context): Boolean =
        isAccepted(context) && prefs(context).getBoolean(KEY_APRS_PUBLIC_ACCEPTED, false)

    /**
     * 记录同意。
     *
     * @param location 是否同意处理精确位置；核心定位功能必须为 true 才能继续
     * @param aprsPublic 是否同意 APRS 位置公开；可选，默认 false
     */
    fun accept(context: Context, location: Boolean, aprsPublic: Boolean) {
        prefs(context).edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putBoolean(KEY_POLICY_ACCEPTED, true)
            .putBoolean(KEY_LOCATION_ACCEPTED, location)
            .putBoolean(KEY_APRS_PUBLIC_ACCEPTED, aprsPublic)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            // 用 commit 而不是 apply：同意后要立即初始化高德 SDK，必须确保落盘完成
            .commit()
    }

    /** 在 APRS 持照/公开声明中单独确认“位置公开”后调用。 */
    fun setAprsPublicAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_APRS_PUBLIC_ACCEPTED, accepted)
            .commit()
    }

    /** 撤回同意（用于“关于 → 隐私政策”中的撤回入口；调用方负责重启/退出）。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
