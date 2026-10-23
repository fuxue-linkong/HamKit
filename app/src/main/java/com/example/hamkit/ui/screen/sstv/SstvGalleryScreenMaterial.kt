package com.example.hamkit.ui.screen.sstv

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.data.sstv.SstvImageStore
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.component.material.SegmentedColumn
import com.example.hamkit.ui.component.material.SegmentedListItem
import com.example.hamkit.ui.viewmodel.SstvViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * SSTV 图库的 Material（Material 3）风格实现。
 *
 * 与 Miuix 版逐项对应：说明文本 / 接收录音卡片 / 空态提示 / 缩略图网格 / 全屏查看 /
 * 删除与清空确认。行为完全一致（同一个 [SstvViewModel]）：缩略图解码仍在 ViewModel 的
 * 协程里完成，本页只负责展示，不引入任何主线程文件 IO。
 */
@Composable
fun SstvGalleryScreenMaterial(
    pickForTx: Boolean = false,
    onPicked: (SstvImageStore.Entry) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<SstvImageStore.Entry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // 列表变化后补齐缺失的缩略图（已在 ViewModel 内去重与协程化）
    LaunchedEffect(uiState.gallery.size) { viewModel.loadThumbnails() }
    // 录音列表与图库一起刷新（两者都是「接收留下的东西」）
    LaunchedEffect(Unit) { viewModel.refreshRecordings() }

    // 查看器状态（viewing/viewingBitmap）存在应用级 SstvViewModel 里，不随本页销毁。
    // 离开图库时若不收起，它会在返回栈里继续存活：下次从接收页返回图库、或再次点
    // 「图库」进入时，会直接落在大图（图 3），而不是图库列表（图 1）。
    DisposableEffect(Unit) {
        onDispose { viewModel.closeViewer() }
    }

    // 大图展开时，系统返回键先收起查看器回到列表，而不是把整个图库弹出返回栈。
    BackHandler(enabled = uiState.viewing != null) {
        viewModel.closeViewer()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pickForTx) "选择要发射的图像" else "SSTV 图库") },
                navigationIcon = {
                    IconButton(onClick = {
                        // 与系统返回一致：大图展开时先回列表，否则才退出图库
                        if (uiState.viewing != null) viewModel.closeViewer() else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (!pickForTx && uiState.gallery.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) {
                            Text("清空")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // 用 Box 包一层：全屏查看要盖在列表之上，两者必须是同一个布局父节点的兄弟
        Box(modifier = Modifier.fillMaxSize()) {
            // 整页只有一个滚动容器（LazyVerticalGrid），说明与录音卡片都用 item() 承载。
            // 不能写成 Column(verticalScroll) { ... LazyVerticalGrid(...) }：
            // 那会让网格在**无限高度约束**下测量，Compose 直接抛 IllegalStateException
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (pickForTx) 2 else 3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── 表头：张数与最近一次操作结果 ──
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            text = if (uiState.gallery.isEmpty()) {
                                "还没有解码历史。接收或导入一段录音后，图像会自动出现在这里。"
                            } else {
                                "共 ${uiState.gallery.size} 张 · ${formatBytes(uiState.galleryBytes)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        //
                        uiState.status
                            .takeIf { uiState.gallery.isNotEmpty() && it.isNotBlank() }
                            ?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                    }
                }

                // ── 接收录音（「录下过境 → 事后重解」的入口）──
                if (uiState.recordings.isNotEmpty()) {
                    item(key = "recordings", span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "接收录音",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${uiState.recordingsLabel}。录音多为带 VIS 头的按帧接收，可直接重解 —— " +
                                        "当时没解好的帧还有机会救回来；持续接收的录音从任意时刻开始，" +
                                        "重解时用盲扫识别模式。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                SegmentedColumn(
                                    content = uiState.recordings.take(5).map { entry ->
                                        {
                                            SegmentedListItem(
                                                headlineContent = {
                                                    Text(entry.modeName.ifBlank { "未识别模式" })
                                                },
                                                supportingContent = {
                                                    Text(
                                                        "${MATERIAL_STAMP_FORMAT.format(Date(entry.timestamp))} · " +
                                                            "${"%.0f".format(entry.seconds)} 秒 · " +
                                                            formatBytes(entry.sizeBytes),
                                                    )
                                                },
                                                trailingContent = {
                                                    Row {
                                                        TextButton(onClick = { viewModel.decodeRecording(entry) }) {
                                                            Text("重解")
                                                        }
                                                        TextButton(onClick = { viewModel.deleteRecording(entry) }) {
                                                            Text("删除")
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    },
                                )
                                if (uiState.recordings.size > 5) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "另有 ${uiState.recordings.size - 5} 段未列出",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.clearRecordings() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("清空全部录音")
                                }
                            }
                        }
                    }
                }

                // ── 空态提示 ──
                if (uiState.gallery.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "提示",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "• 「接收」页可实时解码电台/卫星的 SSTV 信号\n" +
                                        "• 「导入」可解码已录好的 WAV 录音\n" +
                                        "• 收满一帧后自动保存的前提是设置里开启了「自动保存」",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ── 缩略图网格 ──
                items(uiState.gallery, key = { it.file.name }) { entry ->
                    GalleryTileMaterial(
                        entry = entry,
                        thumbnail = uiState.thumbnails[entry.file.name],
                        onClick = {
                            if (pickForTx) {
                                viewModel.setTxFromGallery(entry)
                                onPicked(entry)
                            } else {
                                viewModel.openViewer(entry)
                            }
                        },
                        onDelete = if (pickForTx) null else ({ pendingDelete = entry }),
                    )
                }
            }

            // ── 全屏查看（可左右滑动切上一张 / 下一张）──
            uiState.viewing?.let { current ->
                GalleryPagerMaterial(
                    gallery = uiState.gallery,
                    current = current,
                    bitmap = uiState.viewingBitmap,
                    onSelect = { viewModel.openViewer(it) },
                    onClose = { viewModel.closeViewer() },
                    onDelete = { pendingDelete = it },
                    onExport = { viewModel.exportToGallery(it) },
                    onShare = { entry ->
                        val uri = viewModel.shareUri(entry)
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享 SSTV 图像"))
                        }
                    },
                )
            }
        }
    }

    // ── 删除确认 ──
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除图像") },
            text = { Text("确定删除 ${entry.file.name}？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteImage(entry)
                        if (uiState.viewing?.file == entry.file) viewModel.closeViewer()
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // ── 清空确认 ──
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空图库") },
            text = { Text("将删除全部 ${uiState.gallery.size} 张图像，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearGallery()
                        confirmClear = false
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/** 网格中的一格：缩略图 + 模式名 + 时间 + 删除。 */
@Composable
private fun GalleryTileMaterial(
    entry: SstvImageStore.Entry,
    thumbnail: android.graphics.Bitmap?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            // 缩略图单独占一行：删除按钮不再盖在图上，改到下方信息行
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x22000000)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumbnail
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = entry.modeName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = "载入中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // 信息行：左侧模式名与时间，右侧删除按钮（不遮挡缩略图）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.modeName.ifBlank { "未知模式" } +
                            if (entry.enhanced) " · 增强" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = MATERIAL_STAMP_FORMAT.format(Date(entry.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * 全屏查看的分页容器（Material 版）：左右滑动切换相邻图像。
 *
 * 与 Miuix 版同构：只解码当前张的大图，翻页回调 [onSelect] 让 ViewModel 换图。
 */
@Composable
private fun GalleryPagerMaterial(
    gallery: List<SstvImageStore.Entry>,
    current: SstvImageStore.Entry,
    bitmap: android.graphics.Bitmap?,
    onSelect: (SstvImageStore.Entry) -> Unit,
    onClose: () -> Unit,
    onDelete: (SstvImageStore.Entry) -> Unit,
    onExport: (SstvImageStore.Entry) -> Unit,
    onShare: (SstvImageStore.Entry) -> Unit,
) {
    val currentIndex = gallery.indexOfFirst { it.file.name == current.file.name }
        .coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { gallery.size },
    )
    val scope = rememberCoroutineScope()

    // 外部换图（删除后自动切到下一张）时把 pager 同步过去
    LaunchedEffect(currentIndex) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != currentIndex) {
            pagerState.scrollToPage(currentIndex)
        }
    }
    // 用户滑动后回调 ViewModel 换图
    LaunchedEffect(pagerState.currentPage) {
        gallery.getOrNull(pagerState.currentPage)?.let { page ->
            if (page.file.name != current.file.name) onSelect(page)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { index -> gallery.getOrNull(index)?.file?.name ?: index },
    ) { index ->
        val entry = gallery.getOrNull(index) ?: return@HorizontalPager
        GalleryViewerMaterial(
            entry = entry,
            bitmap = if (index == currentIndex) bitmap else null,
            loadingText = if (index == currentIndex) "正在解码大图…" else "松开后加载…",
            onClose = onClose,
            onDelete = { onDelete(entry) },
            onExport = { onExport(entry) },
            onShare = { onShare(entry) },
            onPrev = if (index > 0) {
                { scope.launch { pagerState.animateScrollToPage(index - 1) } }
            } else {
                null
            },
            onNext = if (index < gallery.lastIndex) {
                { scope.launch { pagerState.animateScrollToPage(index + 1) } }
            } else {
                null
            },
            position = "${index + 1}/${gallery.size}",
        )
    }
}

/** 全屏查看：大图 + 操作按钮。 */
@Composable
private fun GalleryViewerMaterial(
    entry: SstvImageStore.Entry,
    bitmap: android.graphics.Bitmap?,
    loadingText: String,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    position: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0000000)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.modeName.ifBlank { "未知模式" } + if (entry.enhanced) "（增强）" else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = "${MATERIAL_STAMP_FORMAT.format(Date(entry.timestamp))} · " +
                            "${formatBytes(entry.sizeBytes)} · $position",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB0B0B0),
                    )
                }
                if (onPrev != null) {
                    TextButton(onClick = onPrev) { Text("上一张") }
                }
                if (onNext != null) {
                    TextButton(onClick = onNext) { Text("下一张") }
                }
                TextButton(onClick = onClose) { Text("关闭") }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = entry.modeName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = loadingText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text("分享")
                }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Text("导出到相册")
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 时间戳格式（Material 版）。
 *
 * 与 Miuix 版的 [STAMP_FORMAT] 是两份私有常量 —— `private val` 是文件级可见性，
 * 无法跨文件共享，而为一个格式化器把它提成 internal 并不值得。
 */
private val MATERIAL_STAMP_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
