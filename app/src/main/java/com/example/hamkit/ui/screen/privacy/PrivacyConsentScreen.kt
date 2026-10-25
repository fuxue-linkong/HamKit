package com.example.hamkit.ui.screen.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode

/**
 * 首次启动的隐私同意页（按 [UiMode] 分发到 Miuix / Material 两套实现）。
 *
 * 设计要点：
 * - 在用户点击“同意并继续”之前，不初始化高德 SDK、不请求定位/麦克风权限；
 * - 精确位置作为敏感个人信息单独勾选，不与隐私政策捆绑；
 * - APRS 位置公开是可选勾选，默认关闭；
 * - 不同意时退出应用，不写入任何同意状态。
 *
 * 页面状态（三项勾选 + 是否正在看政策全文）集中在本函数，两套 UI 只负责渲染与回调，
 * 避免切换实现（或未来热切换主题）时丢失已填内容。
 */
@Composable
fun PrivacyConsentScreen(
    onAgree: (aprsPublic: Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    BackHandler(onBack = onDecline)

    var policyAccepted by remember { mutableStateOf(false) }
    var locationAccepted by remember { mutableStateOf(false) }
    var aprsPublicAccepted by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }

    // 政策全文与同意页共用同一视觉体系：外层已由 HamKitTheme 提供主题
    if (showPolicy) {
        PrivacyPolicyScreen(onBack = { showPolicy = false })
        return
    }

    // 隐私政策与精确位置是必选项；APRS 公开可选，不影响继续
    val canContinue = policyAccepted && locationAccepted

    when (LocalUiMode.current) {
        UiMode.Miuix -> PrivacyConsentScreenMiuix(
            policyAccepted = policyAccepted,
            locationAccepted = locationAccepted,
            aprsPublicAccepted = aprsPublicAccepted,
            onPolicyCheckedChange = { policyAccepted = it },
            onLocationCheckedChange = { locationAccepted = it },
            onAprsCheckedChange = { aprsPublicAccepted = it },
            canContinue = canContinue,
            onViewPolicy = { showPolicy = true },
            onAgree = { onAgree(aprsPublicAccepted) },
            onDecline = onDecline,
        )

        UiMode.Material -> PrivacyConsentScreenMaterial(
            policyAccepted = policyAccepted,
            locationAccepted = locationAccepted,
            aprsPublicAccepted = aprsPublicAccepted,
            onPolicyCheckedChange = { policyAccepted = it },
            onLocationCheckedChange = { locationAccepted = it },
            onAprsCheckedChange = { aprsPublicAccepted = it },
            canContinue = canContinue,
            onViewPolicy = { showPolicy = true },
            onAgree = { onAgree(aprsPublicAccepted) },
            onDecline = onDecline,
        )
    }
}
