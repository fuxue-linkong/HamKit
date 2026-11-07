package com.example.hamkit.ui.screen.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.hamkit.R
import com.example.hamkit.data.onboarding.OnboardingStore
import com.example.hamkit.data.privacy.AmapPrivacyInitializer
import com.example.hamkit.data.privacy.PrivacyConsentStore
import com.example.hamkit.ui.screen.privacy.PrivacyConsentScreen

/**
 * 首次使用引导：欢迎 → 功能 → 权限 → 协议。
 *
 * 权限步骤可跳过；协议步骤复用 [PrivacyConsentScreen]（必须勾选才能进入）。
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onDecline: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    if (step == 3) {
        PrivacyConsentScreen(
            onAgree = { aprsPublic ->
                PrivacyConsentStore.accept(context, location = true, aprsPublic = aprsPublic)
                OnboardingStore.markDone(context)
                AmapPrivacyInitializer.initialize(context)
                onFinish()
            },
            onDecline = onDecline,
        )
        return
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (step) {
                        0 -> WelcomeStep()
                        1 -> FeatureStep()
                        else -> PermissionStep()
                    }
                }
                Button(
                    onClick = { step += 1 },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            when (step) {
                                0 -> R.string.onboarding_start
                                1 -> R.string.onboarding_next
                                else -> R.string.onboarding_continue
                            },
                        ),
                    )
                }
                if (step == 2) {
                    TextButton(
                        onClick = { step = 3 },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.onboarding_later))
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(48.dp))
    Text(
        "HamKit",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.onboarding_welcome_desc),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FeatureStep() {
    Text(
        stringResource(R.string.onboarding_features_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(16.dp))
    listOf(
        "🛰️" to "卫星：过境预测、实时仰角、转发器",
        "📻" to "FT8 / APRS：解码与台站",
        "📝" to "QSO 日志：检索与记录",
        "🤖" to "AI 助手：问答与工具调用",
    ).forEach { (icon, text) ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(icon, style = MaterialTheme.typography.titleLarge)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PermissionStep() {
    val context = LocalContext.current
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Text(
        stringResource(R.string.onboarding_permission_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_permission_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    PermissionRow(
        title = stringResource(R.string.onboarding_location),
        desc = stringResource(R.string.onboarding_location_desc),
        granted = hasLocation(context),
    ) {
        locationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionRow(
            title = stringResource(R.string.onboarding_notification),
            desc = stringResource(R.string.onboarding_notification_desc),
            granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Text(
                    stringResource(R.string.onboarding_granted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                OutlinedButton(onClick = onRequest) {
                    Text(stringResource(R.string.onboarding_allow))
                }
            }
        }
    }
}

private fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
