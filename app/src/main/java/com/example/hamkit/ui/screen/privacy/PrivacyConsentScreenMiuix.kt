package com.example.hamkit.ui.screen.privacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次启动隐私同意页（Miuix 风格）。
 *
 * 与 [PrivacyConsentScreenMaterial] 共享同一套状态与业务约束，仅视觉层不同：
 * - 说明文字分卡片承载，勾选项集中在第三张卡片，贴合 MIUI/HyperOS 设置页观感；
 * - 主操作使用 miuix 主色按钮（`buttonColorsPrimary`），禁用态由 miuix 自动降级；
 * - 页面自身不持有勾选状态，状态由 [PrivacyConsentScreen] 统一管理。
 *
 * @param canContinue 隐私政策与精确位置两项均已勾选时才允许继续
 */
@Composable
fun PrivacyConsentScreenMiuix(
    policyAccepted: Boolean,
    locationAccepted: Boolean,
    aprsPublicAccepted: Boolean,
    onPolicyCheckedChange: (Boolean) -> Unit,
    onLocationCheckedChange: (Boolean) -> Unit,
    onAprsCheckedChange: (Boolean) -> Unit,
    canContinue: Boolean,
    onViewPolicy: () -> Unit,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles

    Scaffold(
        // 无 topBar，系统栏留白由 Scaffold 的 contentWindowInsets 统一给出
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.privacy_consent_title),
                style = textStyles.headline1,
                color = colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.privacy_consent_intro),
                style = textStyles.body2,
                color = colorScheme.onSurfaceSecondary,
            )

            Spacer(Modifier.height(16.dp))
            ConsentInfoCard(
                title = stringResource(R.string.privacy_consent_location_title),
                body = stringResource(R.string.privacy_consent_location_desc),
            )
            Spacer(Modifier.height(12.dp))
            ConsentInfoCard(
                title = stringResource(R.string.privacy_consent_aprs_title),
                body = stringResource(R.string.privacy_consent_aprs_desc),
            )

            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    ConsentCheckboxRow(
                        checked = policyAccepted,
                        onCheckedChange = onPolicyCheckedChange,
                        text = stringResource(R.string.privacy_consent_policy_checkbox),
                    )
                    ConsentCheckboxRow(
                        checked = locationAccepted,
                        onCheckedChange = onLocationCheckedChange,
                        text = stringResource(R.string.privacy_consent_location_checkbox),
                    )
                    ConsentCheckboxRow(
                        checked = aprsPublicAccepted,
                        onCheckedChange = onAprsCheckedChange,
                        text = stringResource(R.string.privacy_consent_aprs_checkbox),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.privacy_consent_view_policy),
                onClick = onViewPolicy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                textStyle = textStyles.body2,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth(),
                enabled = canContinue,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = stringResource(R.string.privacy_consent_agree),
                    style = textStyles.body1,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                text = stringResource(R.string.privacy_consent_decline),
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                textStyle = textStyles.body2,
            )

            Spacer(Modifier.height(12.dp))
            // 退出/撤回渠道等说明，字号降一级，避免与正文争夺注意力
            Text(
                text = stringResource(R.string.privacy_consent_footer),
                style = textStyles.footnote2,
                color = colorScheme.onSurfaceSecondary,
            )
        }
    }
}

/** 说明卡片：标题 + 正文，两段式排版与设置页卡片一致。 */
@Composable
private fun ConsentInfoCard(title: String, body: String) {
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = textStyles.body1,
                color = colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = textStyles.footnote1,
                color = colorScheme.onSurfaceSecondary,
            )
        }
    }
}

/**
 * 勾选行。
 *
 * 整行可点（`toggleable` + [Role.Checkbox]），Checkbox 自身也绑定同一回调；
 * 点 Checkbox 时事件被内层消费、不会与外层重复触发，因此勾选状态只会翻转一次。
 */
@Composable
private fun ConsentCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
) {
    val colorScheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = ToggleableState(checked),
            onClick = { onCheckedChange(!checked) },
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
