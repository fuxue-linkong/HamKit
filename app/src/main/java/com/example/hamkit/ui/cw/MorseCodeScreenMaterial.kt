package com.example.hamkit.ui.cw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hamkit.R
import com.example.hamkit.ui.component.material.SnackBarHost
import kotlinx.coroutines.launch

/**
 * 摩斯电码编解码页的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版 [MorseCodeScreenMiuix] 逐项对应：
 *  - 编解码卡片：方向切换（编码 / 解码，选中项填充按钮、未选中项文字按钮）、输入框、
 *    支持范围提示、执行按钮、「执行结果」只读输出框 + 一键复制；
 *  - 对照表卡片：标题 + A-Z / 0-9 电码表（FlowRow 流式排列）；
 *  - 空输入时用 Snackbar 提示（文案 [R.string.morsecode_empty_input]），复制成功用 Toast。
 *
 * 状态字段（mode / input / output）、判定与转换逻辑（[encode] / [decode]）、文案、
 * 回调与 Miuix 版完全一致 —— 转换逻辑直接复用 Miuix 文件里的同一份实现，
 * 不另抄一份，杜绝两套风格的行为分叉。
 *
 * 颜色由调用方注入 Material 主题色（见 CWPracticeRouteScreenMaterial：
 * bgPage = surface、bgCard = surfaceContainerHigh、primaryColor = primary、
 * textPrimary = onSurface、textSecondary = onSurfaceVariant），因此本文件不直接读取
 * `MaterialTheme.colorScheme`；其余控件配色走 material3 组件默认值（即 Material 主题色）。
 *
 * 本页是子页面，外层 [CWPracticeRouteScreenMaterial] 已提供 Scaffold 与顶栏，
 * 这里只渲染内容并消费 contentPadding。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MorseCodeScreenMaterial(
    bgPage: Color,
    bgCard: Color,
    primaryColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    contentPadding: PaddingValues
) {
    // mode = true 表示「文本 → 电码」编码方向，与 Miuix 版同一约定
    var mode by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 编解码卡片 ──
            MorseCodeCardMaterial(containerColor = bgCard) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 已选中的方向：点击不做事（与 Miuix 版 onClick = {} 一致）
                    if (mode) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.morsecode_encode))
                        }
                    } else {
                        // 未选中的方向：透明底 + 次要文字色，点击切回编码并清空两侧内容
                        TextButton(
                            onClick = {
                                mode = true
                                input = ""
                                output = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.morsecode_encode),
                                color = textSecondary
                            )
                        }
                    }

                    if (!mode) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.morsecode_decode))
                        }
                    } else {
                        TextButton(
                            onClick = {
                                mode = false
                                input = ""
                                output = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.morsecode_decode),
                                color = textSecondary
                            )
                        }
                    }
                }

                // 输入框：编码方向用文本键盘，解码方向用 ASCII 键盘（与 Miuix 版同一判定）
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (mode) {
                                stringResource(R.string.morsecode_input_hint_text)
                            } else {
                                stringResource(R.string.morsecode_input_hint_morse)
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (mode) KeyboardType.Text else KeyboardType.Ascii
                    ),
                    singleLine = false,
                    maxLines = 4
                )

                Text(
                    text = stringResource(R.string.morsecode_support_hint),
                    color = textSecondary,
                    fontSize = 12.sp
                )

                // 执行转换：空输入只提示不计算；否则按当前方向转换
                Button(
                    onClick = {
                        if (input.isBlank()) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.morsecode_empty_input)
                                )
                            }
                        } else {
                            output = if (mode) encode(input) else decode(input)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (mode) {
                            stringResource(R.string.morsecode_encode)
                        } else {
                            stringResource(R.string.morsecode_decode)
                        }
                    )
                }

                // 输出框：只读，无标签（对应 Miuix 版的 label = ""），右上角复制按钮
                OutlinedTextField(
                    value = output,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                val clip = ClipData.newPlainText("morse_code", output)
                                clipboard.setPrimaryClip(clip)
                                Toast
                                    .makeText(
                                        context,
                                        context.getString(R.string.morsecode_copied),
                                        Toast.LENGTH_SHORT
                                    )
                                    .show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.morsecode_copy),
                                tint = primaryColor
                            )
                        }
                    },
                    singleLine = false,
                    maxLines = 4
                )
            }

            // ── 摩斯电码对照表 ──
            MorseCodeCardMaterial(containerColor = bgCard) {
                Text(
                    text = stringResource(R.string.morsecode_reference_table),
                    color = textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 顺序与 Miuix 版一致（morseMap 是保持插入顺序的 LinkedHashMap）
                    morseMap.forEach { (char, code) ->
                        Text(
                            text = "$char $code",
                            color = textSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.widthIn(min = 72.dp),
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }

            // 底部留白，避免最后一张卡片贴住内容区下沿
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 空输入提示：贴内容区底部，与 Miuix 版同一位置
        SnackBarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding)
        )
    }
}

/**
 * 统一的卡片外壳：Material 版两张卡片共用同一套底色、圆角与内边距，
 * 对应 Miuix 版的 `Surface(shape = RoundedCornerShape(16.dp)) + Column(padding(16.dp))`。
 */
@Composable
private fun MorseCodeCardMaterial(
    containerColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}
