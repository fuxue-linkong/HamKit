package com.example.hamkit.data.onboarding

import android.content.Context
import com.example.hamkit.data.privacy.PrivacyConsentStore

/**
 * 首次引导状态。
 *
 * - v2 起：引导完成 = 欢迎/功能/权限都看过 + 同意协议；
 * - 已同意 v1 隐私政策的用户升级后只弹协议更新页。
 */
object OnboardingStore {

    const val AGREEMENT_VERSION = 2

    private const val PREFS_NAME = "onboarding"
    private const val KEY_VERSION = "version"
    private const val KEY_DONE = "done"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDone(context: Context): Boolean {
        val p = prefs(context)
        return p.getInt(KEY_VERSION, 0) >= AGREEMENT_VERSION && p.getBoolean(KEY_DONE, false)
    }

    fun markDone(context: Context) {
        prefs(context).edit()
            .putInt(KEY_VERSION, AGREEMENT_VERSION)
            .putBoolean(KEY_DONE, true)
            .commit()
    }

    /** 老用户（已同意 v1 隐私政策）需要补看新版协议。 */
    fun needsAgreementUpdate(context: Context): Boolean =
        !isDone(context) && PrivacyConsentStore.isAccepted(context)
}
