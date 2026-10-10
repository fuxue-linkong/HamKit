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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.viewmodel.Ft8ViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Ft8SettingsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = appViewModel<Ft8ViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config = uiState.config

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "FT8 设置",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 同 AprsSettingsScreen：边到边下 IME inset 需在 Compose 层消费，
                // 否则键盘会遮挡呼号 / 网格输入框（HK-BUG-001 同类页面）
                .imePadding()
                .imeNestedScroll()
                .overScrollVertical()
                .scrollEndHaptic()
                .padding(horizontal = 16.dp)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // 身份组
            SectionTitle("身份")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    TextField(
                        value = config.callsign,
                        onValueChange = { newValue ->
                            val filtered = newValue.uppercase().filter { it.isLetterOrDigit() }
                            if (filtered.length <= 12) {
                                viewModel.updateSettings { it.copy(callsign = filtered) }
                            }
                        },
                        label = "呼号 (Callsign)",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = config.grid,
                        onValueChange = { newValue ->
                            val filtered = newValue.uppercase().filter { it.isLetterOrDigit() }
                            if (filtered.length <= 6) {
                                viewModel.updateSettings { it.copy(grid = filtered) }
                            }
                        },
                        label = "网格 (Grid)",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                }
            }

            // 波段组
            Spacer(Modifier.height(16.dp))
            SectionTitle("波段")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    for (band in com.example.hamkit.data.ft8.Ft8Band.entries) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                band.displayName,
                                style = MiuixTheme.textStyles.body1,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = config.band == band,
                                onCheckedChange = { checked ->
                                    if (checked) viewModel.setBand(band)
                                }
                            )
                        }
                    }
                }
            }

            // 发射开关
            Spacer(Modifier.height(16.dp))
            SectionTitle("发射")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "允许发射",
                                style = MiuixTheme.textStyles.body1,
                                color = colorScheme.onSurface
                            )
                            Text(
                                "启用后可在 FT8 模式下发送消息",
                                style = MiuixTheme.textStyles.footnote1,
                                color = colorScheme.onSurfaceSecondary
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = config.txEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateSettings { it.copy(txEnabled = enabled) }
                            }
                        )
                    }
                }
            }

            // 信息卡
            Spacer(Modifier.height(16.dp))
            SectionTitle("FT8 编码信息")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "FT8 采用 8-FSK GFSK 调制（BT=2.0），每周期 15 秒发送 79 个音调。音频采样率 12000 Hz，音调间隔 6.25 Hz。编码使用标准 (174,91) LDPC 纠错码 + CRC-14，与 WSJT-X / FT8CN 完全互通。",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MiuixTheme.textStyles.body1,
        color = colorScheme.onSurfaceSecondary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
