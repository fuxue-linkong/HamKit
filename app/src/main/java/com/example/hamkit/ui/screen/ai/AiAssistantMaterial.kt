package com.example.hamkit.ui.screen.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.ai.AiRole
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.AiAssistantViewModel
import com.example.hamkit.ui.viewmodel.AiChatMessageUi
import com.example.hamkit.ui.viewmodel.AiChatUiState

/** Material 风格实现，与 Miuix 版共用 [AiAssistantViewModel]。 */
@Composable
fun AiAssistantScreenMaterial(bottomInnerPadding: Dp = 0.dp) {
    val viewModel = appViewModel<AiAssistantViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val listState = rememberLazyListState()
    // 与 Miuix 版一致：让出底部导航高度，键盘弹出时取 ime 与导航栏的较大值
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomInset = maxOf(bottomInnerPadding, imeBottom)

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 助手", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = viewModel::newSession) { Text("新对话") }
                    TextButton(onClick = { navigator.push(Route.AiSettings) }) { Text("设置") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!state.initialized) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            if (!state.hasKey) {
                KeyGuideCardMaterial(onOpenSettings = { navigator.push(Route.AiSettings) })
            }
            state.error?.let { message ->
                ErrorCardMaterial(message = message, onDismiss = viewModel::consumeError)
            }

            if (state.messages.isEmpty()) {
                EmptyStateMaterial(onPick = { prompt -> viewModel.onInputChange(prompt) })
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageItemMaterial(message)
                    }
                }
            }

            ComposerMaterial(
                modifier = Modifier.padding(bottom = bottomInset),
                input = state.input,
                streaming = state.isStreaming,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
            )
        }
    }
}

@Composable
private fun KeyGuideCardMaterial(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("还没有配置 API Key", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "AI 助手使用你自己的 API Key。默认预设是 DeepSeek，也可以切换其它服务商或内网地址。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenSettings) { Text("去配置") }
        }
    }
}

@Composable
private fun ErrorCardMaterial(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun EmptyStateMaterial(onPick: (String) -> Unit) {
    val samples = listOf(
        "业余无线电 CQ 分区怎么划分？",
        "FT8 的 73 和 RR73 是什么意思？",
        "ISS 的 SSTV 活动一般用什么模式？",
        "如何选择 HF 天线的长度？",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("HamKit AI", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "可以问业余无线电知识，也可以让我调用 App 功能：查双区定位、算卫星过境、开 SSTV 接收。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            samples.forEach { sample ->
                TextButton(
                    onClick = { onPick(sample) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        sample,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageItemMaterial(message: AiChatMessageUi) {
    if (message.role == AiRole.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (message.isStreaming) "正在生成…" else "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message.elapsedMs?.let { elapsed ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${elapsed / 1000.0} s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                message.text.ifBlank { if (message.isStreaming) "…" else "（无文本输出）" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.text.isBlank() && !message.isStreaming) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            message.error?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ComposerMaterial(
    modifier: Modifier = Modifier,
    input: String,
    streaming: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("问问业余无线电…") },
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        if (streaming) {
            Button(onClick = onStop) { Text("停止") }
        } else {
            Button(onClick = onSend, enabled = input.isNotBlank()) { Text("发送") }
        }
    }
}
