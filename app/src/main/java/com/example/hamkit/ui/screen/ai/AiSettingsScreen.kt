package com.example.hamkit.ui.screen.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.ai.AiPreset
import com.example.hamkit.data.ai.tool.AiToolRegistry
import com.example.hamkit.data.ai.AiProtocol
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.viewmodel.AiSettingsViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * AI 设置页（从「设置」或 AI 页右上角进入）。
 *
 * 风格分发入口；两种风格共用 [AiSettingsViewModel]。
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit = {}) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> AiSettingsScreenMiuix(onBack)
        UiMode.Material -> AiSettingsScreenMaterial(onBack)
    }
}

@Composable
fun AiSettingsScreenMiuix(onBack: () -> Unit = {}) {
    val viewModel = appViewModel<AiSettingsViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "AI 助手设置",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground,
                        )
                    }
                },
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCardMiuix(state = state)
            PresetCardMiuix(
                selected = state.config.preset,
                onSelect = viewModel::selectPreset,
            )
            ProtocolCardMiuix(
                selected = state.config.protocol,
                onSelect = viewModel::selectProtocol,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("连接", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Base URL", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(4.dp))
                    TextField(
                        value = state.config.baseUrl,
                        onValueChange = viewModel::setBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("模型", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(4.dp))
                    TextField(
                        value = state.config.model,
                        onValueChange = viewModel::setModel,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextButton(
                            text = if (state.fetchingModels) "获取中…" else "获取模型列表",
                            onClick = viewModel::fetchModels,
                            enabled = !state.fetchingModels,
                        )
                        state.modelsError?.let {
                            Text(it, fontSize = 10.sp, color = colorScheme.error)
                        }
                    }
                    if (state.models.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.models.forEach { model ->
                                ChipMiuix(
                                    text = model,
                                    selected = model == state.config.model,
                                    onClick = { viewModel.setModel(model) },
                                )
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("API Key", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.keyMasked?.let { "已保存：$it" } ?: "尚未保存",
                        fontSize = 11.sp,
                        color = if (state.hasKey) colorScheme.onSurfaceVariantSummary else colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                viewModel.saveKey(keyInput)
                                keyInput = ""
                            },
                            enabled = keyInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("保存 Key")
                        }
                        TextButton(text = "清除", onClick = viewModel::clearKey)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Key 用 Android Keystore AES-GCM 加密后保存在本机，不写日志、不随备份导出。",
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SwitchPreference(
                        title = "允许明文 HTTP（仅内网）",
                        summary = "只对 10.x / 172.16-31.x / 192.168.x / .local 生效；HTTP 不加密。",
                        startAction = {},
                        checked = state.config.allowCleartext,
                        onCheckedChange = viewModel::setAllowCleartext,
                    )
                    SwitchPreference(
                        title = "保存对话历史",
                        summary = "历史用 AES-256-GCM 加密保存在本机。",
                        startAction = {},
                        checked = state.config.saveHistory,
                        onCheckedChange = viewModel::setSaveHistory,
                    )
                    ArrowPreference(
                        title = "清除所有对话",
                        summary = "删除本机加密历史，不可恢复。",
                        onClick = viewModel::clearHistory,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("系统提示词", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "留空使用默认提示词（已包含 App 能力、工具清单与附件规则）。",
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = state.config.systemPrompt,
                        onValueChange = viewModel::setSystemPrompt,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("工具权限", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "关闭后模型看不到该工具，也不会调用它。",
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    AiToolRegistry.ALL.forEach { tool ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tool.spec.title, fontSize = 12.sp)
                                Text(
                                    tool.spec.name,
                                    fontSize = 9.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Switch(
                                checked = tool.spec.name !in state.config.disabledTools,
                                onCheckedChange = { enabled ->
                                    viewModel.toggleTool(tool.spec.name, enabled)
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存设置")
                }
                Button(
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f),
                    enabled = !state.testing,
                ) {
                    Text(if (state.testing) "测试中…" else "测试连接")
                }
            }

            ProbeResultMiuix(state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCardMiuix(state: com.example.hamkit.ui.viewmodel.AiSettingsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Text(
                    "${state.config.preset.label} · ${state.config.model}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        state.probe?.ok == true -> "已连接"
                        state.probe != null -> "连接失败"
                        else -> "未测试"
                    },
                    fontSize = 11.sp,
                    color = if (state.probe?.ok == true) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "协议：${state.config.protocol.label} · " +
                    if (state.config.allowCleartext) "允许内网 HTTP" else "仅 HTTPS",
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun PresetCardMiuix(
    selected: AiPreset,
    onSelect: (AiPreset) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("服务商", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiPreset.entries.forEach { preset ->
                    ChipMiuix(
                        text = preset.label,
                        selected = preset == selected,
                        onClick = { onSelect(preset) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolCardMiuix(
    selected: AiProtocol,
    onSelect: (AiProtocol) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("API 协议", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProtocol.entries.forEach { protocol ->
                    ChipMiuix(
                        text = protocol.label,
                        selected = protocol == selected,
                        onClick = { onSelect(protocol) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipMiuix(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) colorScheme.onPrimary else colorScheme.onSurface,
        modifier = Modifier
            .background(
                if (selected) colorScheme.primary else colorScheme.surfaceVariant,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ProbeResultMiuix(state: com.example.hamkit.ui.viewmodel.AiSettingsUiState) {
    val probe = state.probe
    state.notice?.let { notice ->
        Text(notice, fontSize = 11.sp, color = colorScheme.primary)
    }
    state.error?.let { error ->
        Text(error, fontSize = 11.sp, color = colorScheme.error)
    }
    if (probe != null) {
        Text(
            if (probe.ok) {
                "连接成功，首包延迟 ${probe.latencyMs} ms"
            } else {
                "连接失败：${probe.error?.message ?: "未知错误"}"
            },
            fontSize = 11.sp,
            color = if (probe.ok) colorScheme.primary else colorScheme.error,
        )
    }
}
