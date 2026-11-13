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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.R
import com.example.hamkit.data.ai.AiModelProfile
import com.example.hamkit.data.ai.AiPreset
import com.example.hamkit.data.ai.tool.AiToolRegistry
import com.example.hamkit.data.ai.AiProtocol
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.component.dialog.rememberConfirmDialog
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import com.example.hamkit.ui.viewmodel.AiSettingsUiState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.Switch
import androidx.compose.foundation.border

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
    var showModelPicker by remember { mutableStateOf(false) }
    // C4 整改：清除历史是破坏性操作，必须二次确认
    val clearHistoryConfirm = rememberConfirmDialog(
        onConfirm = { viewModel.clearHistory() },
    )

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
            ActiveModelCardMiuix(
                state = state,
                onSwitch = { showModelPicker = true },
            )
            if (showModelPicker) {
                ModelPickerDialogMiuix(
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
            ModelConfigCardMiuix(
                state = state,
                keyInput = keyInput,
                onKeyInputChange = { keyInput = it },
                onNameChange = viewModel::setProfileName,
                onPreset = viewModel::selectPreset,
                onProtocol = viewModel::selectProtocol,
                onBaseUrl = viewModel::setBaseUrl,
                onModelChange = viewModel::updateModel,
                onModelDelete = viewModel::deleteModel,
                onModelAdd = viewModel::addModel,
                onModelSelect = viewModel::selectModel,
                onFetchModels = viewModel::fetchModels,
                onSaveKey = {
                    viewModel.saveKey(keyInput)
                    keyInput = ""
                },
                onSave = { viewModel.save() },
                onCancel = viewModel::resetToSaved,
                onAddProvider = viewModel::addProfile,
            )
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
                        title = "信任自签证书（仅内网）",
                        summary = "用于自签 HTTPS / 反向代理；只对私有地址生效，公网仍强制校验。",
                        startAction = {},
                        checked = state.config.allowSelfSigned,
                        onCheckedChange = viewModel::setAllowSelfSigned,
                    )
                    SwitchPreference(
                        title = "保存对话历史",
                        summary = stringResource(R.string.ai_settings_history_hint),
                        startAction = {},
                        checked = state.config.saveHistory,
                        onCheckedChange = viewModel::setSaveHistory,
                    )
                    // C4：字符串在组合期解析后传给 onClick，回调内不能调用 @Composable
                    val clearTitle = stringResource(R.string.ai_settings_clear_history)
                    val clearDesc = stringResource(R.string.ai_settings_clear_history_hint)
                    val clearLabel = stringResource(R.string.ai_settings_clear)
                    val cancelLabel = stringResource(R.string.cancel)
                    ArrowPreference(
                        title = clearTitle,
                        summary = clearDesc,
                        onClick = {
                            clearHistoryConfirm.showConfirm(
                                title = clearTitle,
                                content = clearDesc,
                                confirm = clearLabel,
                                dismiss = cancelLabel,
                            )
                        },
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.ai_settings_system_prompt), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.ai_settings_prompt_hint),
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
                    Text(stringResource(R.string.ai_settings_tools), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.ai_settings_tools_hint),
                        fontSize = 10.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.ai_settings_confirm),
                        summary = stringResource(R.string.ai_settings_confirm_hint),
                        startAction = {},
                        checked = state.config.confirmSideEffects,
                        onCheckedChange = viewModel::setConfirmSideEffects,
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
                    onClick = viewModel::testConnection,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.testing,
                ) {
                    Text(if (state.testing) "测试中…" else stringResource(R.string.ai_settings_test))
                }
            }

            McpCardMiuix(
                state = state,
                onToggle = viewModel::setMcpEnabled,
                onPort = viewModel::setMcpPort,
                onResetToken = viewModel::resetMcpToken,
            )
            ProbeResultMiuix(state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCardMiuix(state: com.example.hamkit.ui.viewmodel.AiSettingsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            val activeProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }
            val selectedEntry = state.editingModels.firstOrNull { it.id == state.editingModelId }
            Row {
                Text(
                    "${activeProfile?.name ?: state.config.preset.label} · " +
                        (selectedEntry?.label ?: stringResource(R.string.ai_model_none_selected)),
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
    probe?.toolsSupported?.let { supported ->
        Text(
            if (supported) stringResource(R.string.ai_settings_tool_ok) else stringResource(R.string.ai_settings_tool_unsupported),
            fontSize = 11.sp,
            color = if (supported) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
        )
    }
    if (probe != null) {
        Text(
            if (probe.ok) {
                stringResource(R.string.ai_settings_probe_ok, probe.latencyMs.toInt())
            } else {
                stringResource(R.string.ai_settings_probe_fail, probe.error?.message ?: "?")
            },
            fontSize = 11.sp,
            color = if (probe.ok) colorScheme.primary else colorScheme.error,
        )
    }
}

@Composable
private fun ModelListCardMiuix(
    profiles: List<AiModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_models_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.ai_model_new),
                    onClick = onAdd,
                )
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
                        Text(profile.name, fontSize = 13.sp)
                        Text(
                            "${profile.protocol.label} · ${profile.currentModelId}",
                            fontSize = 10.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (profile.id == activeId) {
                        Text(
                            stringResource(R.string.ai_model_current),
                            fontSize = 10.sp,
                            color = colorScheme.primary,
                        )
                    }
                    if (profiles.size > 1) {
                        TextButton(
                            text = stringResource(R.string.ai_model_delete),
                            onClick = { onDelete(profile.id) },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.ai_models_hint),
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ActiveModelCardMiuix(state: AiSettingsUiState, onSwitch: () -> Unit) {
    val active = state.profiles.firstOrNull { it.id == state.activeProfileId }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        active?.name ?: "默认",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.ai_model_current),
                        fontSize = 10.sp,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .background(
                                colorScheme.primary.copy(alpha = 0.10f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.config.protocol.label} · ${state.config.model}",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            TextButton(
                text = stringResource(R.string.ai_model_switch),
                onClick = onSwitch,
            )
        }
    }
}

@Composable
private fun ModelConfigCardMiuix(
    state: AiSettingsUiState,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onPreset: (AiPreset) -> Unit,
    onProtocol: (AiProtocol) -> Unit,
    onBaseUrl: (String) -> Unit,
    onModelChange: (String, String, String) -> Unit,
    onModelDelete: (String) -> Unit,
    onModelAdd: () -> Unit,
    onModelSelect: (String) -> Unit,
    onFetchModels: () -> Unit,
    onSaveKey: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onAddProvider: () -> Unit,
) {
    var customApi by remember { mutableStateOf(false) }
    val activeName = state.profiles.firstOrNull { it.id == state.activeProfileId }?.name.orEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.ai_models_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(false to stringResource(R.string.ai_model_preset_tab), true to stringResource(R.string.ai_model_custom_tab))
                    .forEach { (isCustom, label) ->
                        val selected = customApi == isCustom
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) colorScheme.primary.copy(alpha = 0.10f)
                                    else colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                )
                                .clickable { customApi = isCustom }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
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
                        ChipMiuix(
                            text = preset.label,
                            selected = preset == state.config.preset,
                            onClick = { onPreset(preset) },
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
                        ChipMiuix(
                            text = protocol.label,
                            selected = protocol == state.config.protocol,
                            onClick = { onProtocol(protocol) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Base URL", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(4.dp))
                TextField(
                    value = state.config.baseUrl,
                    onValueChange = onBaseUrl,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                Text(
                    stringResource(R.string.ai_url_hint),
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
            }

            Text(
                stringResource(R.string.ai_model_name),
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(4.dp))
            TextField(
                value = activeName,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_model_catalog),
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (state.fetchingModels) {
                        stringResource(R.string.ai_settings_fetching)
                    } else {
                        stringResource(R.string.ai_settings_fetch_models)
                    },
                    onClick = onFetchModels,
                    enabled = !state.fetchingModels,
                )
            }
            Text(
                if (state.models.isEmpty()) {
                    stringResource(R.string.ai_model_catalog_empty)
                } else {
                    "已获取 ${state.models.size} 个模型，已自动加入下方列表"
                },
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            state.modelsError?.let {
                Text(
                    it,
                    fontSize = 10.sp,
                    color = colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            DividerMiuix()

            if (state.editingModels.isEmpty()) {
                Text(
                    stringResource(R.string.ai_model_empty_hint),
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            state.editingModels.forEach { entry ->
                val selected = entry.id == state.editingModelId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) colorScheme.primary.copy(alpha = 0.04f)
                            else colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(10.dp),
                        )
                        .then(
                            if (selected) {
                                Modifier.border(1.dp, colorScheme.primary, RoundedCornerShape(10.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onModelSelect(entry.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("≡", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.width(6.dp))
                        TextField(
                            value = entry.modelId,
                            onValueChange = { onModelChange(entry.id, it, entry.displayName.orEmpty()) },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { onModelDelete(entry.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.ai_model_delete),
                                tint = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(24.dp))
                        Text(
                            "显示名称",
                            fontSize = 10.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.width(52.dp),
                        )
                        TextField(
                            value = entry.displayName.orEmpty(),
                            onValueChange = { onModelChange(entry.id, entry.modelId, it) },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            TextButton(
                text = stringResource(R.string.ai_model_new),
                onClick = onModelAdd,
            )
            DividerMiuix()
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.ai_settings_key_hint),
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.keyMasked?.let { "已保存：$it" } ?: stringResource(R.string.ai_settings_not_saved),
                    fontSize = 11.sp,
                    color = if (state.hasKey) colorScheme.onSurfaceVariantSummary else colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            TextField(
                value = keyInput,
                onValueChange = onKeyInputChange,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSaveKey,
                    enabled = keyInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.ai_settings_save_key))
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.ai_model_cancel),
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                )
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    enabled = !state.saving,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        if (state.saving) {
                            stringResource(R.string.ai_model_saving)
                        } else {
                            stringResource(R.string.ai_model_save)
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .dashedBorder(colorScheme.onSurface.copy(alpha = 0.22f))
                    .clickable(onClick = onAddProvider)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.ai_model_provider_add),
                    fontSize = 12.sp,
                    color = colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ModelPickerDialogMiuix(
    profiles: List<AiModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .background(colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_models_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.ai_model_new),
                    onClick = onAdd,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (profiles.isEmpty()) {
                Text(
                    stringResource(R.string.ai_model_empty),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(profiles, key = { it.id }) { profile ->
                        val current = profile.id == activeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (current) colorScheme.primary.copy(alpha = 0.08f)
                                    else Color.Transparent,
                                )
                                .clickable { onSelect(profile.id) }
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, fontSize = 13.sp)
                                Text(
                                    "${profile.protocol.label} · ${profile.currentModelId}",
                                    fontSize = 10.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            if (current) {
                                Text(
                                    stringResource(R.string.ai_model_current),
                                    fontSize = 10.sp,
                                    color = colorScheme.primary,
                                )
                            }
                            if (profiles.size > 1) {
                                TextButton(
                                    text = stringResource(R.string.ai_model_delete),
                                    onClick = { onDelete(profile.id) },
                                )
                            }
                        }
                    }
                }
            }
            TextButton(text = stringResource(R.string.ai_close), onClick = onDismiss)
        }
    }
}

private fun Modifier.dashedBorder(color: Color, radius: Dp = 12.dp): Modifier = this.drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
        ),
        cornerRadius = CornerRadius(radius.toPx()),
    )
}

@Composable
private fun McpCardMiuix(
    state: AiSettingsUiState,
    onToggle: (Boolean) -> Unit,
    onPort: (Int) -> Unit,
    onResetToken: () -> Unit,
) {
    var portInput by remember(state.mcpPort) { mutableStateOf(state.mcpPort.toString()) }
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "局域网 MCP",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.mcpEnabled, onCheckedChange = onToggle)
            }
            Text(
                if (state.mcpRunning) "运行中" else "已关闭",
                fontSize = 10.sp,
                color = if (state.mcpRunning) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
            )
            if (state.mcpRunning) {
                Spacer(Modifier.height(8.dp))
                Text("地址", fontSize = 10.sp, color = colorScheme.onSurfaceVariantSummary)
                Text(
                    state.mcpAddress.ifBlank { "请连接 Wi-Fi 后重试" },
                    fontSize = 11.sp,
                    color = colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text("Token", fontSize = 10.sp, color = colorScheme.onSurfaceVariantSummary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.mcpToken,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "复制",
                        onClick = { clipboard.setText(AnnotatedString(state.mcpToken)) },
                    )
                    TextButton(
                        text = "重置",
                        onClick = onResetToken,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("端口", fontSize = 10.sp, color = colorScheme.onSurfaceVariantSummary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "应用",
                        onClick = { onPort(portInput.toIntOrNull() ?: state.mcpPort) },
                    )
                }
            }
            state.mcpError?.let {
                Text(it, fontSize = 10.sp, color = colorScheme.error)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "仅只读工具；写操作（QSO/提醒）不开放。仅限可信局域网。",
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun DividerMiuix() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}
