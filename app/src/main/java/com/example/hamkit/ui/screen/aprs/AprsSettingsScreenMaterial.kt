package com.example.hamkit.ui.screen.aprs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedListItem
import com.example.hamkit.ui.component.material.SegmentedSwitchItem
import com.example.hamkit.ui.component.material.SegmentedTextField
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.AprsViewModel

/**
 * APRS 设置页（Material 3 风格）。
 *
 * 与 [AprsSettingsScreenMiuix] 逐项对应，回调一字不改：
 * - 身份：呼号（输入即转大写）、SSID（附「完整呼号」预览）、Passcode（合规 HK-BUG-003：
 *   只接受用户自行持有的 passcode，留空即以只读方式连接）；
 * - APRS-IS 服务器：地址与端口；
 * - 位置上报：评论、发送间隔、符号选择入口、启用位置上报开关；
 * - 最近一次错误提示。
 *
 * 表单用项目自有的分段列表组件（[SegmentedTextField] / [SegmentedListItem] /
 * [SegmentedSwitchItem]）承载，与设置页其余页面的 Material 观感保持一致。
 *
 * 两套实现共享同一个 [AprsViewModel] 实例，因此切换界面风格不会丢已填写的配置。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AprsSettingsScreenMaterial(
    onNavigateBack: () -> Unit = {},
    onNavigate: (Route) -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<AprsViewModel>()
    val config by viewModel.settings.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APRS 设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 边到边（compileSdk 37 → Android 15+ 强制）下窗口不再被 IME 顶起，
                // Manifest 的 adjustResize 已失效，IME inset 必须在 Compose 层消费：
                // imePadding 把键盘高度变为可滚动的底部内边距，底部输入项才能滚入可见区。
                .imePadding()
                // 键盘弹出/收起时把 IME 嵌套滚动交给本容器，
                // 聚焦的输入项可自动滚入可见区域（否则只能手动拖动）——与 Miuix 版同一处理。
                .imeNestedScroll()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // 身份组
            SegmentedColumn(
                title = "身份",
                content = listOf<@Composable () -> Unit>(
                    {
                        SegmentedTextField(
                            label = "呼号 (Callsign)",
                            value = config.callsign,
                            // 业余呼号一律大写，与 Miuix 版同样在写入时 uppercase
                            onValueChange = {
                                viewModel.updateSettings { c -> c.copy(callsign = it.uppercase()) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = null,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters
                            )
                        )
                    },
                    {
                        SegmentedTextField(
                            label = "SSID",
                            value = config.ssid,
                            onValueChange = {
                                viewModel.updateSettings { c -> c.copy(ssid = it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            // Miuix 版把「完整呼号」放在 SSID 输入框右侧，这里作为该项的辅助文案。
                            // 拼接规则与 Miuix 版逐字一致：呼号为空显示 "-"，有 SSID 才追加 "-SSID"。
                            supportingContent = {
                                Text(
                                    "完整呼号: ${config.callsign.ifEmpty { "-" }}" +
                                        "${if (config.ssid.isNotEmpty()) "-${config.ssid}" else ""}"
                                )
                            }
                        )
                    },
                    {
                        SegmentedTextField(
                            // 合规（HK-BUG-003）：不再展示/计算任何 passcode。
                            // 用户必须自行持有 APRS-IS passcode；留空即以只读方式连接（pass -1）。
                            label = stringResource(R.string.aprs_passcode_label),
                            value = config.passcode,
                            onValueChange = {
                                viewModel.updateSettings { c -> c.copy(passcode = it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    },
                )
            )

            Spacer(Modifier.height(8.dp))

            // 只读 / 已持凭据的说明文案：文案随 passcode 是否为空切换，与 Miuix 版同一判断
            Text(
                text = stringResource(
                    if (config.isReadOnly) {
                        R.string.aprs_passcode_readonly_hint
                    } else {
                        R.string.aprs_passcode_held_hint
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(16.dp))

            // 服务器组
            SegmentedColumn(
                title = "APRS-IS 服务器",
                content = listOf<@Composable () -> Unit>(
                    {
                        SegmentedTextField(
                            label = "服务器地址",
                            value = config.server,
                            onValueChange = {
                                viewModel.updateSettings { c -> c.copy(server = it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = null
                        )
                    },
                    {
                        SegmentedTextField(
                            label = "端口",
                            value = config.port.toString(),
                            // 端口非法输入（空串/非数字）直接忽略，保留上一次有效值
                            onValueChange = {
                                it.toIntOrNull()?.let { p ->
                                    viewModel.updateSettings { c -> c.copy(port = p) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    },
                )
            )

            Spacer(Modifier.height(16.dp))

            // 位置上报组
            SegmentedColumn(
                title = "位置上报",
                content = listOf<@Composable () -> Unit>(
                    {
                        SegmentedTextField(
                            label = "评论",
                            value = config.comment,
                            onValueChange = {
                                viewModel.updateSettings { c -> c.copy(comment = it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = null
                        )
                    },
                    {
                        SegmentedTextField(
                            label = "发送间隔 (秒)",
                            value = config.transmitInterval.toString(),
                            // 同端口：非法输入忽略；P2-12 写入前 clamp 到下限 30 秒，避免 0 形成定位+发报紧循环
                            onValueChange = {
                                it.toIntOrNull()?.let { i ->
                                    viewModel.updateSettings { c -> c.copy(transmitInterval = i.coerceAtLeast(30)) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    },
                    {
                        // 符号选择入口：展示当前符号 table+code，点击进入符号选择页
                        SegmentedListItem(
                            onClick = { onNavigate(Route.AprsSymbolPicker) },
                            headlineContent = { Text("符号") },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${config.symbolTable}${config.symbolCode}",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = " ›",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    },
                    {
                        SegmentedSwitchItem(
                            title = "启用位置上报",
                            checked = config.enableTransmit,
                            onCheckedChange = { v ->
                                viewModel.updateSettings { c -> c.copy(enableTransmit = v) }
                            }
                        )
                    },
                )
            )

            lastError?.let { error ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "错误: $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
