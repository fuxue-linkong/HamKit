package com.example.hamkit.ui.screen.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.hamkit.R

/**
 * 首次启动的隐私同意页。
 *
 * 设计要点：
 * - 在用户点击“同意并继续”之前，不初始化高德 SDK、不请求定位/麦克风权限；
 * - 精确位置作为敏感个人信息单独勾选，不与隐私政策捆绑；
 * - APRS 位置公开是可选勾选，默认关闭；
 * - 不同意时退出应用，不写入任何同意状态。
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

    val dark = isSystemInDarkTheme()
    if (showPolicy) {
        MaterialTheme(
            colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        ) {
            PrivacyPolicyScreen(onBack = { showPolicy = false })
        }
        return
    }

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Text(
                    text = stringResource(R.string.privacy_consent_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.privacy_consent_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))
                ConsentSection(
                    title = stringResource(R.string.privacy_consent_location_title),
                    body = stringResource(R.string.privacy_consent_location_desc),
                )
                Spacer(Modifier.height(12.dp))
                ConsentSection(
                    title = stringResource(R.string.privacy_consent_aprs_title),
                    body = stringResource(R.string.privacy_consent_aprs_desc),
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                ConsentCheckbox(
                    checked = policyAccepted,
                    onCheckedChange = { policyAccepted = it },
                    text = stringResource(R.string.privacy_consent_policy_checkbox),
                )
                ConsentCheckbox(
                    checked = locationAccepted,
                    onCheckedChange = { locationAccepted = it },
                    text = stringResource(R.string.privacy_consent_location_checkbox),
                )
                ConsentCheckbox(
                    checked = aprsPublicAccepted,
                    onCheckedChange = { aprsPublicAccepted = it },
                    text = stringResource(R.string.privacy_consent_aprs_checkbox),
                )

                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showPolicy = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.privacy_consent_view_policy))
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onAgree(aprsPublicAccepted) },
                    enabled = policyAccepted && locationAccepted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.privacy_consent_agree))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.privacy_consent_decline))
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.privacy_consent_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun ConsentSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConsentCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
