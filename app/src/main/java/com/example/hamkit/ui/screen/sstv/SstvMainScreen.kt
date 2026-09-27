package com.example.hamkit.ui.screen.sstv

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SstvViewModel
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * SSTV 接收主界面：实时预览 + 状态 + 控制。
 *
 * 图像会随接收进度自上而下逐步显现（每 [SstvRecorder] 部分解码一次刷新一屏）。
 *
 * @param presetModeName 由卫星详情页预置的模式名（如 ISS 的 `PD_120`）；非空时锁定该模式
 */
@Composable
fun SstvMainScreen(
    presetModeName: String? = null,
    onNavigate: (Route) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = appViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    // 由卫星详情页进入时预置模式：ISS 等 SSTV 卫星可直接锁定其常用模式。
    // 走 applyPreset（不写设置）而非 setManualMode，并在离开页面时清除 ——
    // ViewModel 是应用级单例，否则预置会一直黏住，让首页进入也变成手动模式。
    LaunchedEffect(presetModeName) {
        val mode = presetModeName?.let { name -> SstvMode.entries.find { it.name == name } }
        if (mode != null) viewModel.applyPreset(mode)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.clearPreset() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startReceiving() else viewModel.onPermissionDenied()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "SSTV 慢扫描电视",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    Text(
                        "设置",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable { onNavigate(Route.SstvSettings) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .overScrollVertical()
                .scrollEndHaptic()
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── 图像预览 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val aspect = uiState.mode?.let { it.linePixels.toFloat() / it.imageLines } ?: (4f / 3f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = uiState.bitmap
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "SSTV 解码图像",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(
                                text = "等待信号…\n把手机扬声器侧贴近电台，音量适中",
                                style = MiuixTheme.textStyles.body1,
                                color = Color.White,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 进度条（用简单的两段 Box 实现，避免依赖具体进度条组件）
                    val progress = uiState.progress.coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x33000000)),
                    ) {
                        if (progress > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(6.dp)
                                    .background(colorScheme.primary),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = uiState.status,
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onBackground,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 接收参数 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("接收参数", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("模式", uiState.mode?.displayName ?: "待识别")
                    InfoRow(
                        "VIS 码",
                        uiState.visCode?.let { "0x${it.toString(16).uppercase()}" } ?: "—",
                    )
                    InfoRow("失谐补偿", "${uiState.hedrShiftHz.roundToInt()} Hz")
                    InfoRow("行周期比", "%.4f".format(uiState.slantRatio))
                    InfoRow("同步脉冲", "${uiState.syncPulseCount} 个")
                    InfoRow("接收进度", "${uiState.decodedLines} / ${uiState.totalLines} 行")
                    uiState.savedFileName?.let { InfoRow("已保存", it) }
                    uiState.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MiuixTheme.textStyles.body2, color = Color(0xFFD9534F))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 控制按钮 ──
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (uiState.isReceiving) {
                            viewModel.stopReceiving()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) viewModel.startReceiving() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = if (uiState.isReceiving) {
                        ButtonDefaults.buttonColors(color = Color.Transparent, contentColor = MiuixTheme.colorScheme.onSurfaceSecondary)
                    } else {
                        ButtonDefaults.buttonColorsPrimary()
                    },
                ) {
                    Text(if (uiState.isReceiving) "停止接收" else "开始接收")
                }
                Button(
                    onClick = { viewModel.saveCurrent() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存图像")
                }
                Button(
                    onClick = { viewModel.clearImage() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 模式选择（VIS 失败时的兜底）──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("模式选择", style = MiuixTheme.textStyles.title4, color = colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (uiState.presetApplied) {
                            "当前为卫星预置模式，仅本次接收有效；选择任意模式即可取消"
                        } else {
                            "默认自动识别 VIS；弱信号下可手动锁定模式"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (uiState.presetApplied) colorScheme.primary else colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            onClick = { viewModel.setManualMode(null) },
                            colors = if (uiState.manualModeName == null) {
                                ButtonDefaults.buttonColorsPrimary()
                            } else {
                                ButtonDefaults.buttonColors(color = Color.Transparent, contentColor = MiuixTheme.colorScheme.onSurfaceSecondary)
                            },
                        ) {
                            Text("自动")
                        }
                        SstvMode.DECODABLE_MODES.forEach { mode ->
                            Button(
                                onClick = { viewModel.setManualMode(mode) },
                                colors = if (uiState.manualModeName == mode.name) {
                                    ButtonDefaults.buttonColorsPrimary()
                                } else {
                                    ButtonDefaults.buttonColors(color = Color.Transparent, contentColor = MiuixTheme.colorScheme.onSurfaceSecondary)
                                },
                            ) {
                                Text(mode.displayName)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = colorScheme.onBackground,
            modifier = Modifier.weight(1.4f),
        )
    }
}
