package com.example.hamkit.ui.screen.aprs

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.AprsViewModel
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
fun AprsSettingsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigate: (Route) -> Unit = {}
) {
    val viewModel = appViewModel<AprsViewModel>()
    val config by viewModel.settings.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "APRS 设置",
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
                // 边到边（compileSdk 37 → Android 15+ 强制）下窗口不再被 IME 顶起，
                // Manifest 的 adjustResize 已失效，IME inset 必须在 Compose 层消费：
                // imePadding 把键盘高度变为可滚动的底部内边距，底部输入框才能滚入可见区。
                .imePadding()
                // 键盘弹出/收起时把 IME 嵌套滚动交给本容器，
                // 聚焦的 TextField 可自动滚入可见区域（否则只能手动拖动）。
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
                        onValueChange = { viewModel.updateSettings { c -> c.copy(callsign = it.uppercase()) } },
                        label = "呼号 (Callsign)",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = config.ssid,
                            onValueChange = { viewModel.updateSettings { c -> c.copy(ssid = it) } },
                            label = "SSID",
                            modifier = Modifier.width(80.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "完整呼号: ${config.callsign.ifEmpty { "-" }}${if (config.ssid.isNotEmpty()) "-${config.ssid}" else ""}",
                            style = MiuixTheme.textStyles.body2,
                            color = colorScheme.onSurfaceSecondary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 合规（HK-BUG-003）：不再展示/计算任何 passcode。
                    // 用户必须自行持有 APRS-IS passcode；留空即以只读方式连接（pass -1）。
                    TextField(
                        value = config.passcode,
                        onValueChange = { viewModel.updateSettings { c -> c.copy(passcode = it) } },
                        label = stringResource(R.string.aprs_passcode_label),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            if (config.isReadOnly) {
                                R.string.aprs_passcode_readonly_hint
                            } else {
                                R.string.aprs_passcode_held_hint
                            }
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceSecondary
                    )
                }
            }

            // 服务器组
            Spacer(Modifier.height(16.dp))
            SectionTitle("APRS-IS 服务器")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    TextField(
                        value = config.server,
                        onValueChange = { viewModel.updateSettings { c -> c.copy(server = it) } },
                        label = "服务器地址",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = config.port.toString(),
                        onValueChange = { it.toIntOrNull()?.let { p -> viewModel.updateSettings { c -> c.copy(port = p) } } },
                        label = "端口",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            // 位置上报组
            Spacer(Modifier.height(16.dp))
            SectionTitle("位置上报")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    TextField(
                        value = config.comment,
                        onValueChange = { viewModel.updateSettings { c -> c.copy(comment = it) } },
                        label = "评论",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = config.transmitInterval.toString(),
                        onValueChange = { it.toIntOrNull()?.let { i -> viewModel.updateSettings { c -> c.copy(transmitInterval = i) } } },
                        label = "发送间隔 (秒)",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(Modifier.height(8.dp))
                    // 符号选择入口
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(Route.AprsSymbolPicker) }
                    ) {
                        Text(
                            "符号",
                            style = MiuixTheme.textStyles.body1,
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${config.symbolTable}${config.symbolCode}",
                            style = MiuixTheme.textStyles.title1,
                            color = colorScheme.primary
                        )
                        Text(
                            " ›",
                            style = MiuixTheme.textStyles.title2,
                            color = colorScheme.onSurfaceSecondary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "启用位置上报",
                            style = MiuixTheme.textStyles.body1,
                            color = colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = config.enableTransmit,
                            onCheckedChange = { v -> viewModel.updateSettings { c -> c.copy(enableTransmit = v) } }
                        )
                    }
                }
            }

            lastError?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "错误: $error",
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.error
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.body1,
        color = colorScheme.onSurfaceSecondary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
