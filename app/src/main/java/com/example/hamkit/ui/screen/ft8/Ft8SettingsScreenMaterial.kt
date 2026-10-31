package com.example.hamkit.ui.screen.ft8

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.ft8.Ft8Band
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.component.material.ExpressiveSwitch
import com.example.hamkit.ui.viewmodel.Ft8ViewModel

/**
 * FT8 设置页（Material 3 风格）。
 *
 * 与 [Ft8SettingsScreenMiuix] 一一对应：身份（呼号 / 网格）、波段单选、发射开关、编码说明。
 * 校验规则（呼号 ≤12、网格 ≤6，仅字母数字）与 Miuix 版完全相同。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Ft8SettingsScreenMaterial(
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<Ft8ViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config = uiState.config

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FT8 设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        )
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 边到边下 IME inset 需在 Compose 层消费，否则键盘会遮挡呼号 / 网格输入框（HK-BUG-001）
                .imePadding()
                .imeNestedScroll()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 身份组
            Ft8SettingsSectionTitleMaterial("身份")
            Ft8SettingsCardMaterial {
                OutlinedTextField(
                    value = config.callsign,
                    onValueChange = { newValue ->
                        val filtered = newValue.uppercase().filter { it.isLetterOrDigit() }
                        if (filtered.length <= 12) {
                            viewModel.updateSettings { it.copy(callsign = filtered) }
                        }
                    },
                    label = { Text("呼号 (Callsign)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.grid,
                    onValueChange = { newValue ->
                        val filtered = newValue.uppercase().filter { it.isLetterOrDigit() }
                        if (filtered.length <= 6) {
                            viewModel.updateSettings { it.copy(grid = filtered) }
                        }
                    },
                    label = { Text("网格 (Grid)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
            }

            // 波段组
            Spacer(Modifier.height(16.dp))
            Ft8SettingsSectionTitleMaterial("波段")
            Ft8SettingsCardMaterial {
                Ft8Band.entries.forEach { band ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = band.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        // 波段是互斥单选：只在勾选时下发 setBand，取消勾选不做任何事（与 Miuix 版一致）
                        ExpressiveSwitch(
                            checked = config.band == band,
                            onCheckedChange = { checked ->
                                if (checked) viewModel.setBand(band)
                            }
                        )
                    }
                }
            }

            // 发射开关
            Spacer(Modifier.height(16.dp))
            Ft8SettingsSectionTitleMaterial("发射")
            Ft8SettingsCardMaterial {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "允许发射（预留）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "目前 FT8 尚无发送功能；此开关仅作预留，暂不影响任何行为",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ExpressiveSwitch(
                        checked = config.txEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateSettings { it.copy(txEnabled = enabled) }
                        }
                    )
                }
            }

            // 信息卡
            Spacer(Modifier.height(16.dp))
            Ft8SettingsSectionTitleMaterial("FT8 编码信息")
            Ft8SettingsCardMaterial {
                Text(
                    text = "FT8 采用 8-FSK GFSK 调制（BT=2.0），每周期 15 秒发送 79 个音调。" +
                            "音频采样率 12000 Hz，音调间隔 6.25 Hz。" +
                            "编码使用标准 (174,91) LDPC 纠错码 + CRC-14，与 WSJT-X / FT8CN 完全互通。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Material 风格卡片：统一使用 surfaceContainerHigh 容器色 */
@Composable
private fun Ft8SettingsCardMaterial(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/** Material 风格分组标题 */
@Composable
private fun Ft8SettingsSectionTitleMaterial(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
