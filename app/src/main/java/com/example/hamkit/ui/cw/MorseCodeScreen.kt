package com.example.hamkit.ui.cw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 编码表与编解码函数改为 internal：Material 版（MorseCodeScreenMaterial.kt）复用同一份，
// 避免把 Unicode 转码逻辑抄成两份而产生行为分叉。仅放宽可见性，实现一字未改。
internal val morseMap = mapOf(
    'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
    'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
    'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
    'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
    'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
    'Z' to "--..",
    '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
    '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----."
)

internal val reverseMorseMap = morseMap.entries.associate { (k, v) -> v to k }

// 十六进制字符集：用于 Unicode 转码（中文等非摩斯字符走此路径）
internal val hexChars = "0123456789ABCDEF"

internal fun encode(text: String): String {
    return text.uppercase()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" / ") { word ->
            word.flatMap { ch ->
                if (ch in morseMap) {
                    // ASCII 字符直接编码
                    listOf(morseMap[ch]!!)
                } else if (ch.code <= 0x10FFFF) {
                    // 非摩斯字符（如中文）才走转义：转为 Unicode 十六进制后逐位编码，
                    // 用 U 前缀标识。表内字符（含 U 自身）已在上面直接编码，避免歧义。
                    val hex = Integer.toHexString(ch.code).uppercase()
                    listOf(morseMap['U']!!) + hex.map { morseMap[it]!! }
                } else {
                    // 超出 Unicode 范围的非法 Char，跳过
                    emptyList()
                }
            }.joinToString(" ")
        }
}

internal fun decode(morse: String): String {
    return morse.trim()
        .split("/")
        .joinToString(" ") { word ->
            val codes = word.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val sb = StringBuilder()
            var i = 0
            while (i < codes.size) {
                if (codes[i] == morseMap['U'] && i + 1 < codes.size) {
                    // U 前缀：后续连续 hex 字符组成一个 Unicode 码点。
                    // 校验 hex 长度（≤6 位）与码点合法性，非法则按普通字符处理，不抛异常。
                    val hexBuilder = StringBuilder()
                    var j = i + 1
                    while (j < codes.size) {
                        val ch = reverseMorseMap[codes[j]]
                        if (ch != null && ch in hexChars) {
                            hexBuilder.append(ch)
                            j++
                        } else break
                    }
                    // hex 位数需为 UTF-16 码元/码点的合法范围（Char.code 最大 6 位十六进制）
                    val hexStr = hexBuilder.toString()
                    val codePoint = hexStr.toIntOrNull(16)
                    if (hexStr.isNotEmpty() && hexStr.length <= 6 &&
                        codePoint != null && Character.isValidCodePoint(codePoint) &&
                        codePoint <= 0x10FFFF && !(codePoint in 0xD800..0xDFFF)
                    ) {
                        sb.appendCodePoint(codePoint)
                        i = j
                    } else {
                        // 非法或无 hex：把 U 按普通字符还原，后续 hex 组照常逐个解码
                        sb.append('U')
                        i++
                    }
                } else {
                    reverseMorseMap[codes[i]]?.let { sb.append(it) }
                    i++
                }
            }
            sb.toString()
        }
        .trim()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MorseCodeScreenMiuix(
    bgPage: Color,
    bgCard: Color,
    primaryColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    contentPadding: PaddingValues
) {
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = bgCard
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (mode) {
                            Button(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary()
                            ) {
                                Text(stringResource(R.string.morsecode_encode))
                            }
                        } else {
                            Button(
                                onClick = { mode = true; input = ""; output = "" },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color.Transparent,
                                    contentColor = textSecondary
                                )
                            ) {
                                Text(stringResource(R.string.morsecode_encode))
                            }
                        }

                        if (!mode) {
                            Button(
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary()
                            ) {
                                Text(stringResource(R.string.morsecode_decode))
                            }
                        } else {
                            Button(
                                onClick = { mode = false; input = ""; output = "" },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color.Transparent,
                                    contentColor = textSecondary
                                )
                            ) {
                                Text(stringResource(R.string.morsecode_decode))
                            }
                        }
                    }

                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = if (mode) stringResource(R.string.morsecode_input_hint_text) else stringResource(R.string.morsecode_input_hint_morse),
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
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (mode) stringResource(R.string.morsecode_encode) else stringResource(R.string.morsecode_decode)
                        )
                    }

                    TextField(
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
                                        .makeText(context, context.getString(R.string.morsecode_copied), Toast.LENGTH_SHORT)
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
                        label = "",
                        singleLine = false,
                        maxLines = 4
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = bgCard
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    SnackbarHost(
        state = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(contentPadding)
    )
    }
}
