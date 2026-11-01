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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.data.ai.AiModelProfile
import com.example.hamkit.data.ai.AiPreset
import com.example.hamkit.data.ai.tool.AiToolRegistry
import com.example.hamkit.data.ai.AiProtocol
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.component.material.SwitchItem
import com.example.hamkit.ui.viewmodel.AiSettingsUiState
import com.example.hamkit.ui.viewmodel.AiSettingsViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog

/** Material 风格实现，与 Miuix 版共用 [AiSettingsViewModel]。 */
@Composable
fun AiSettingsScreenMaterial(onBack: () -> Unit = {}) {
    val viewModel = appViewModel<AiSettingsViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }

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
            ActiveModelCardMaterial(
                state = state,
                onSwitch = { showModelPicker = true },
            )
            if (showModelPicker) {
                ModelPickerDialogMaterial(
                    profiles = state.profiles,
                    activeId = state.activeProfileId,
                    onSelect = { id ->
                        viewModel.selectProfile(id)
                        showModelPicker = false
                    },
                    onAdd = {
                        viewModel.addProfile()
                        showModelPicker = false
                    },
                    onDelete = viewModel::deleteProfile,
                    onDismiss = { showModelPicker = false },
                )
            }
            ModelConfigCardMaterial(
                state = state,
                keyInput = keyInput,
                onKeyInputChange = { keyInput = it },
                onNameChange = viewModel::setProfileName,
                onPreset = viewModel::selectPreset,
                onProtocol = viewModel::selectProtocol,
                onBaseUrl = viewModel::setBaseUrl,
                onModel = viewModel::setModel,
                onFetchModels = viewModel::fetchModels,
                onSaveKey = {
                    viewModel.saveKey(keyInput)
                    keyInput = ""
                },
                onSave = { viewModel.save() },
                onCancel = viewModel::resetToSaved,
            )
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
                        summary = stringResource(R.string.ai_settings_history_hint),
                        checked = state.config.saveHistory,
                        onCheckedChange = viewModel::setSaveHistory,
                    )
                    Row(Modifier.padding(horizontal = 14.dp), Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.ai_settings_clear_history),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearHistory) { Text(stringResource(R.string.ai_settings_clear)) }
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
                        stringResource(R.string.ai_settings_prompt_hint),
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
                        summary = stringResource(R.string.ai_settings_confirm_hint),
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
                    Text(stringResource(R.string.ai_settings_save))
                }
                Button(
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f),
                    enabled = !state.testing,
                ) {
                    Text(if (state.testing) "测试中…" else stringResource(R.string.ai_settings_test))
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
            if (supported) stringResource(R.string.ai_settings_tool_ok) else stringResource(R.string.ai_settings_tool_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = if (supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.probe?.let { probe ->
        Text(
            if (probe.ok) {
                stringResource(R.string.ai_settings_probe_ok, probe.latencyMs.toInt())
            } else {
                stringResource(R.string.ai_settings_probe_fail, probe.error?.message ?: "?")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (probe.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ModelListCardMaterial(
    profiles: List<AiModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_models_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAdd) { Text(stringResource(R.string.ai_model_new)) }
            }
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(profile.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${profile.protocol.label} · ${profile.model}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (profile.id == activeId) {
                        Text(
                            stringResource(R.string.ai_model_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (profiles.size > 1) {
                        TextButton(onClick = { onDelete(profile.id) }) {
                            Text(stringResource(R.string.ai_model_delete))
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.ai_models_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveModelCardMaterial(state: AiSettingsUiState, onSwitch: () -> Unit) {
    val active = state.profiles.firstOrNull { it.id == state.activeProfileId }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        active?.name ?: "默认",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.ai_model_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.config.protocol.label} · ${state.config.model}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSwitch) {
                Text(stringResource(R.string.ai_model_switch))
            }
        }
    }
}

@Composable
private fun ModelConfigCardMaterial(
    state: AiSettingsUiState,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onPreset: (AiPreset) -> Unit,
    onProtocol: (AiProtocol) -> Unit,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSaveKey: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var customApi by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.ai_models_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(3.dp),
            ) {
                listOf(
                    false to stringResource(R.string.ai_model_preset_tab),
                    true to stringResource(R.string.ai_model_custom_tab),
                ).forEach { (isCustom, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (customApi == isCustom) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { customApi = isCustom }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (customApi == isCustom) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (!customApi) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = preset == state.config.preset,
                            onClick = { onPreset(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = protocol == state.config.protocol,
                            onClick = { onProtocol(protocol) },
                            label = { Text(protocol.label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.config.baseUrl,
                    onValueChange = onBaseUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    placeholder = { Text("https://gateway.example/v1") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
            }

            val activeName = state.profiles.firstOrNull { it.id == state.activeProfileId }?.name.orEmpty()
            OutlinedTextField(
                value = activeName,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_model_name)) },
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.config.model,
                onValueChange = onModel,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_model_id)) },
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_model_catalog),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFetchModels, enabled = !state.fetchingModels) {
                    Text(
                        if (state.fetchingModels) {
                            stringResource(R.string.ai_settings_fetching)
                        } else {
                            stringResource(R.string.ai_settings_fetch_models)
                        },
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dashedBorderMaterial(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    .padding(14.dp),
            ) {
                if (state.models.isEmpty()) {
                    Text(
                        stringResource(R.string.ai_model_catalog_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.models.forEach { model ->
                            FilterChip(
                                selected = model == state.config.model,
                                onClick = { onModel(model) },
                                label = { Text(model) },
                            )
                        }
                    }
                }
                state.modelsError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.ai_settings_key_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.keyMasked?.let { "已保存：$it" } ?: stringResource(R.string.ai_settings_not_saved),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.hasKey) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = onKeyInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSaveKey, enabled = keyInput.isNotBlank()) {
                Text(stringResource(R.string.ai_settings_save_key))
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_model_cancel))
                }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_model_save))
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialogMaterial(
    profiles: List<AiModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_models_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                if (profiles.isEmpty()) {
                    Text(stringResource(R.string.ai_model_empty))
                } else {
                    profiles.forEach { profile ->
                        val current = profile.id == activeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (current) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { onSelect(profile.id) }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${profile.protocol.label} · ${profile.model}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (current) {
                                Text(
                                    stringResource(R.string.ai_model_current),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (profiles.size > 1) {
                                TextButton(onClick = { onDelete(profile.id) }) {
                                    Text(stringResource(R.string.ai_model_delete))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.ai_model_new)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_close)) }
        },
    )
}

private fun Modifier.dashedBorderMaterial(color: Color, radius: Dp = 12.dp): Modifier = this.drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
        ),
        cornerRadius = CornerRadius(radius.toPx()),
    )
}
