package com.example.hamkit.ui.screen.privacy

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hamkit.R

/**
 * 首次启动隐私同意页（Material 3 风格）。
 *
 * 视觉与 Miuix 版（[PrivacyConsentScreenMiuix]）对齐，但保留 Material 的分隔线 + 扁平按钮排布。
 * 配色不再自带 `lightColorScheme()/darkColorScheme()`：加载时外层已由
 * `HamKitTheme` 提供配色，这样隐私页也能跟随用户选择的主题色与暗色模式。
 */
@Composable
fun PrivacyConsentScreenMaterial(
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
                onCheckedChange = onPolicyCheckedChange,
                text = stringResource(R.string.privacy_consent_policy_checkbox),
            )
            ConsentCheckbox(
                checked = locationAccepted,
                onCheckedChange = onLocationCheckedChange,
                text = stringResource(R.string.privacy_consent_location_checkbox),
            )
            ConsentCheckbox(
                checked = aprsPublicAccepted,
                onCheckedChange = onAprsCheckedChange,
                text = stringResource(R.string.privacy_consent_aprs_checkbox),
            )

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onViewPolicy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.privacy_consent_view_policy))
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAgree,
                enabled = canContinue,
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
