package com.example.hamkit.ui.screen.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.data.ai.AiPreset
import com.example.hamkit.data.ai.tool.AiToolRegistry
import com.example.hamkit.data.ai.AiProtocol
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.component.material.SwitchItem
import com.example.hamkit.ui.viewmodel.AiSettingsUiState
import com.example.hamkit.ui.viewmodel.AiSettingsViewModel

/** Material 风格实现，与 Miuix 版共用 [AiSettingsViewModel]。 */
@Composable
fun AiSettingsScreenMaterial(onBack: () -> Unit = {}) {
    val viewModel = appViewModel<AiSettingsViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 助手设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row {
                        Text(
                            "${state.config.preset.label} · ${state.config.model}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                state.probe?.ok == true -> "已连接"
                                state.probe != null -> "连接失败"
                                else -> "未测试"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.probe?.ok == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "协议：${state.config.protocol.label} · " +
                            if (state.config.allowCleartext) "允许内网 HTTP" else "仅 HTTPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("服务商", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AiPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = preset == state.config.preset,
                                onClick = { viewModel.selectPreset(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("API 协议", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiProtocol.entries.forEach { protocol ->
                            FilterChip(
                                selected = protocol == state.config.protocol,
                                onClick = { viewModel.selectProtocol(protocol) },
                                label = { Text(protocol.label) },
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = state.config.baseUrl,
                        onValueChange = viewModel::setBaseUrl,
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.config.model,
                        onValueChange = viewModel::setModel,
                        label = { Text("模型") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = viewModel::fetchModels,
                            enabled = !state.fetchingModels,
                        ) {
                            Text(if (state.fetchingModels) "获取中…" else stringResource(R.string.ai_settings_fetch_models))
                        }
                        state.modelsError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (state.models.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.models.forEach { model ->
                                FilterChip(
                                    selected = model == state.config.model,
                                    onClick = { viewModel.setModel(model) },
                                    label = { Text(model) },
                                )
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("API Key", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.keyMasked?.let { "已保存：$it" } ?: "尚未保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.hasKey) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("输入新的 API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                viewModel.saveKey(keyInput)
                                keyInput = ""
                            },
                            enabled = keyInput.isNotBlank(),
                        ) {
                            Text("保存 Key")
                        }
                        TextButton(onClick = viewModel::clearKey) { Text("清除") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Key 用 Android Keystore AES-GCM 加密后保存在本机，不写日志、不随备份导出。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SwitchItem(
                        title = "允许明文 HTTP（仅内网）",
                        summary = "只对 10.x / 172.16-31.x / 192.168.x / .local 生效；HTTP 不加密。",
                        checked = state.config.allowCleartext,
                        onCheckedChange = viewModel::setAllowCleartext,
                    )
                    SwitchItem(
                        title = "保存对话历史",
                        summary = "历史用 AES-256-GCM 加密保存在本机。",
                        checked = state.config.saveHistory,
                        onCheckedChange = viewModel::setSaveHistory,
                    )
                    Row(Modifier.padding(horizontal = 14.dp), Arrangement.SpaceBetween) {
                        Text(
                            "清除所有对话",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearHistory) { Text("清除") }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.ai_settings_system_prompt), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "留空使用默认提示词（已包含 App 能力、工具清单与附件规则）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.config.systemPrompt,
                        onValueChange = viewModel::setSystemPrompt,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        stringResource(R.string.ai_settings_tools),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                    SwitchItem(
                        title = stringResource(R.string.ai_settings_confirm),
                        summary = "创建提醒等写入操作先弹窗确认。",
                        checked = state.config.confirmSideEffects,
                        onCheckedChange = viewModel::setConfirmSideEffects,
                    )
                    AiToolRegistry.ALL.forEach { tool ->
                        SwitchItem(
                            title = tool.spec.title,
                            summary = tool.spec.name,
                            checked = tool.spec.name !in state.config.disabledTools,
                            onCheckedChange = { enabled ->
                                viewModel.toggleTool(tool.spec.name, enabled)
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.weight(1f),
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

            ResultTextMaterial(state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultTextMaterial(state: AiSettingsUiState) {
    state.notice?.let { notice ->
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    state.error?.let { error ->
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    state.probe?.toolsSupported?.let { supported ->
        Text(
            if (supported) "工具调用：可用" else "工具调用：该模型不支持（只能问答）",
            style = MaterialTheme.typography.bodySmall,
            color = if (supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.probe?.let { probe ->
        Text(
            if (probe.ok) {
                "连接成功，首包延迟 ${probe.latencyMs} ms"
            } else {
                "连接失败：${probe.error?.message ?: "未知错误"}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (probe.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
