package com.example.hamkit.ui.screen.aprs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.viewmodel.AprsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APRS 消息页（Material 3 风格）。
 *
 * 与 [AprsMessageScreenMiuix] 行为完全一致：
 * - 进入时按 [partnerCallsign] 过滤会话，并标记该会话已读（未读角标因此清零）；
 * - 新消息到达时自动滚到底部；
 * - 空会话且存在历史会话时先列会话列表；
 * - 底部输入框 + 发送按钮，仅在内容非空且已选定会话时才真正发送。
 */
@Composable
fun AprsMessageScreenMaterial(
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

    // 以消息条数为 key：仅在新消息到达时滚到底，避免每次重组都把用户滚动位置拉回底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // 会话切换或进入页面时标记已读，与 Miuix 版保持同一时机
    LaunchedEffect(selectedPartner) {
        if (selectedPartner.isNotEmpty()) {
            viewModel.markConversationRead(selectedPartner)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selectedPartner.isNotEmpty()) "与 $selectedPartner 的对话" else "APRS 消息")
                },
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
                .padding(innerPadding)
                .padding(start = 16.dp, end = 16.dp)
                // 边到边下键盘不会自动顶起窗口，IME inset 必须在 Compose 层消费，
                // 否则底部输入框会被键盘遮住（与 Miuix 版同一处理）
                .imePadding()
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 未选定会话时列出历史会话，与 Miuix 版同为纯文本条目
            if (selectedPartner.isEmpty() && conversationPartners.isNotEmpty()) {
                LazyColumn {
                    items(
                        items = conversationPartners,
                        key = { partner -> partner }
                    ) { partner ->
                        Text(
                            text = partner,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
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
                items(
                    items = messages,
                    // 消息 id 对应数据库主键，作 key 让历史消息在新增时复用已有布局
                    key = { message -> message.id }
                ) { message ->
                    AprsMessageBubbleMaterial(message = message)
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
                        // 与 Miuix 版同样的守卫：空内容或未选定会话时不发送，也不清空输入
                        if (messageText.isNotBlank() && selectedPartner.isNotEmpty()) {
                            viewModel.sendMessage(selectedPartner, messageText.trim())
                            messageText = ""
                        }
                    }
                ) {
                    Text("发送")
                }
            }
        }
    }
}

/**
 * 单条消息气泡形（Material 3 风格）。
 *
 * 与 Miuix 版逐项对应：发出消息右对齐（→ 收件人），收到消息左对齐（← 发件人），
 * 时间戳与投递状态只在发出消息上展示。
 */
@Composable
private fun AprsMessageBubbleMaterial(message: AprsMessage) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isOutgoing = message.isOutgoing

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                // 贴向说话人一侧的圆角收窄，形成指向发送方的气泡尖角
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateFormat.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            style = MaterialTheme.typography.labelSmall,
                            color = when (message.status) {
                                AprsViewModel.MSG_STATUS_ACKED -> MaterialTheme.colorScheme.primary
                                AprsViewModel.MSG_STATUS_FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}
