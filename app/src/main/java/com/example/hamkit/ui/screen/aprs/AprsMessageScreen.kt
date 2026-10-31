package com.example.hamkit.ui.screen.aprs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.aprs.AprsMessage
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.viewmodel.AprsViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APRS 消息页分发入口：按当前 UI 风格选择 Miuix / Material 实现。
 *
 * 两者共用同一个 application 级 [AprsViewModel] 实例，切换风格不会丢状态。
 */
@Composable
fun AprsMessageScreen(
    partnerCallsign: String = "",
    onNavigateBack: () -> Unit = {}
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> AprsMessageScreenMiuix(
            partnerCallsign = partnerCallsign,
            onNavigateBack = onNavigateBack
        )

        UiMode.Material -> AprsMessageScreenMaterial(
            partnerCallsign = partnerCallsign,
            onNavigateBack = onNavigateBack
        )
    }
}

@Composable
fun AprsMessageScreenMiuix(
    partnerCallsign: String = "",
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<AprsViewModel>()
    val allMessages by viewModel.messages.collectAsStateWithLifecycle()
    val conversationPartners by viewModel.conversationPartners.collectAsStateWithLifecycle()

    val messages = if (partnerCallsign.isNotEmpty()) {
        allMessages.filter { it.source == partnerCallsign || it.destination == partnerCallsign }
    } else {
        emptyList()
    }

    var messageText by remember { mutableStateOf("") }
    var selectedPartner by remember { mutableStateOf(partnerCallsign) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(selectedPartner) {
        if (selectedPartner.isNotEmpty()) {
            viewModel.markConversationRead(selectedPartner)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (selectedPartner.isNotEmpty()) "与 $selectedPartner 的对话" else "APRS 消息",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground
                        )
                    }
                }
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp)
                .padding(top = innerPadding.calculateTopPadding())
                .imePadding()
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            if (selectedPartner.isEmpty() && conversationPartners.isNotEmpty()) {
                LazyColumn {
                    items(conversationPartners) { partner ->
                        Text(
                            text = partner,
                            style = MiuixTheme.textStyles.body1,
                            color = colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                // P1-4：会话条目点击后切换页内 selectedPartner，
                                // 进入对话视图并触发 markConversationRead
                                .clickable { selectedPartner = partner }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState
            ) {
                items(messages) { message ->
                    AprsMessageBubble(message = message)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("消息内容") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (messageText.isNotBlank() && selectedPartner.isNotEmpty()) {
                            viewModel.sendMessage(selectedPartner, messageText.trim())
                            messageText = ""
                        }
                    },
                    content = { Text("发送") }
                )
            }
        }
    }
}

@Composable
private fun AprsMessageBubble(message: AprsMessage) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isOutgoing = message.isOutgoing

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isOutgoing) 12.dp else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else 12.dp
                    )
                )
                .padding(10.dp)
        ) {
            Column {
                Text(
                    text = if (isOutgoing) "→ ${message.destination}" else "← ${message.source}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.body,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateFormat.format(Date(message.timestamp)),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (message.status) {
                                AprsViewModel.MSG_STATUS_ACKED -> "✓✓ 已送达"
                                AprsViewModel.MSG_STATUS_SENT -> "✓ 已发送"
                                AprsViewModel.MSG_STATUS_FAILED -> "⚠ 失败"
                                else -> "⏳ 待发送"
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = when (message.status) {
                                AprsViewModel.MSG_STATUS_ACKED -> MiuixTheme.colorScheme.primary
                                AprsViewModel.MSG_STATUS_FAILED -> MiuixTheme.colorScheme.error
                                else -> MiuixTheme.colorScheme.onSurfaceSecondary
                            }
                        )
                    }
                }
            }
        }
    }
}
