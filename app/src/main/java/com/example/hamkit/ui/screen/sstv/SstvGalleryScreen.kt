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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
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
import kotlinx.coroutines.launch
import com.example.hamkit.data.sstv.SstvImageStore
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.viewmodel.SstvViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * SSTV 图库：浏览、全屏查看、删除、分享、导出到相册。
 *
 * 一期声明过 R4「图库浏览」并在设置页放了「清空图库」按钮，但**从来没有界面能看到图**
 * （数据层 `SstvImageStore` 一直是完整的）。本页补齐这条链路。
 *
 * 设计要点：
 * - **缩略图解码全在协程**：一期曾因把文件 IO 与位图解码放主线程踩过一次
 *   （REQUIREMENT_SSTV.md §11.7 第 6 项），这里统一走 [SstvViewModel.loadThumbnails]；
 * - **列表用缩略图、全屏才解大图**：`BitmapFactory` 的 2 的幂次降采样让列表不会因为
 *   一张大图而 OOM；
 * - **导出与分享分两路**：[SstvImageStore.exportToGallery] 落进系统相册（Android 10+
 *   零权限），分享走 FileProvider 的 `ACTION_SEND`。
 *
 * @param pickForTx 为 true 时进入「为发射选图」模式：点击图像即选中并返回
 * @param onPicked 选中回调（`pickForTx = true` 时使用）
 */
@Composable
fun SstvGalleryScreen(
    pickForTx: Boolean = false,
    onPicked: (SstvImageStore.Entry) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> SstvGalleryScreenMiuix(pickForTx, onPicked, onNavigateBack)
        UiMode.Material -> SstvGalleryScreenMaterial(pickForTx, onPicked, onNavigateBack)
    }
}

/** Miuix（MIUI）风格实现。 */
@Composable
fun SstvGalleryScreenMiuix(
    pickForTx: Boolean = false,
    onPicked: (SstvImageStore.Entry) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    val viewModel = applicationScopedViewModel<SstvViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
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
                title = if (pickForTx) "选择要发射的图像" else "SSTV 图库",
                navigationIcon = {
                    IconButton(onClick = {
                        // 与系统返回一致：大图展开时先回列表，否则才退出图库
                        if (uiState.viewing != null) viewModel.closeViewer() else onNavigateBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    if (!pickForTx && uiState.gallery.isNotEmpty()) {
                        Text(
                            "清空",
                            style = MiuixTheme.textStyles.body1,
                            color = colorScheme.primary,
                            modifier = Modifier
                                .clickable { confirmClear = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { padding ->
        // 用 Box 包一层：全屏查看要盖在列表之上，两者必须是同一个布局父节点的兄弟
        Box(modifier = Modifier.fillMaxSize()) {
            // 整页只有一个滚动容器（LazyVerticalGrid），说明、录音卡片都用 item() 承载。
            // 不能写成 Column(verticalScroll) { ... LazyVerticalGrid(...) }：
            // 那会让网格在**无限高度约束**下测量，Compose 直接抛 IllegalStateException
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (pickForTx) 2 else 3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── 表头：张数与最近一次操作结果 ──
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = if (uiState.gallery.isEmpty()) {
                                "还没有解码历史。接收或导入一段录音后，图像会自动出现在这里。"
                            } else {
                                "共 ${uiState.gallery.size} 张 · ${formatBytes(uiState.galleryBytes)}"
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = colorScheme.onBackground,
                        )
                        // 最近一次操作结果（已保存 / 已删除…）只在有图时显示一行
                        uiState.status
                            .takeIf { uiState.gallery.isNotEmpty() && it.isNotBlank() }
                            ?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    it,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = colorScheme.primary,
                                )
                            }
                    }
                }

                // ── 接收录音（「录下过境 → 事后重解」的入口）──
                if (uiState.recordings.isNotEmpty()) {
                    item(key = "recordings", span = { GridItemSpan(maxLineSpan) }) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "接收录音",
                                    style = MiuixTheme.textStyles.title4,
                                    color = colorScheme.onBackground,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${uiState.recordingsLabel}。录音多为带 VIS 头的按帧接收，可直接重解 —— " +
                                        "当时没解好的帧还有机会救回来；持续接收的录音从任意时刻开始，" +
                                        "重解时用盲扫识别模式。",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = colorScheme.onBackground,
                                )
                                Spacer(Modifier.height(6.dp))
                                uiState.recordings.take(5).forEach { entry ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                entry.modeName.ifBlank { "未识别模式" },
                                                style = MiuixTheme.textStyles.body2,
                                                color = colorScheme.onBackground,
                                            )
                                            Text(
                                                "${STAMP_FORMAT.format(Date(entry.timestamp))} · " +
                                                    "${"%.0f".format(entry.seconds)} 秒 · " +
                                                    formatBytes(entry.sizeBytes),
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = colorScheme.primary,
                                            )
                                        }
                                        TextButton(
                                            text = "重解",
                                            onClick = { viewModel.decodeRecording(entry) },
                                        )
                                        TextButton(
                                            text = "删除",
                                            onClick = { viewModel.deleteRecording(entry) },
                                        )
                                    }
                                }
                                if (uiState.recordings.size > 5) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "另有 ${uiState.recordings.size - 5} 段未列出",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = colorScheme.onBackground,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                // 清空录音不可撤销：文案直接标成错误色，提醒破坏性
                                Button(
                                    onClick = { viewModel.clearRecordings() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("清空全部录音", color = colorScheme.error)
                                }
                            }
                        }
                    }
                }

                // ── 空态提示 ──
                if (uiState.gallery.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "空图库",
                                    style = MiuixTheme.textStyles.title4,
                                    color = colorScheme.onBackground,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "• 「接收」页可实时解码电台/卫星的 SSTV 信号\n" +
                                        "• 「导入」可解码已录好的 WAV 录音\n" +
                                        "• 收满一帧后自动保存的前提是设置里开启了「自动保存」",
                                    style = MiuixTheme.textStyles.body2,
                                    color = colorScheme.onBackground,
                                )
                            }
                        }
                    }
                }

                // ── 缩略图网格 ──
                items(uiState.gallery, key = { it.file.name }) { entry ->
                    GalleryTile(
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
                GalleryPager(
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
        WindowDialog(
            show = true,
            title = "删除图像",
            onDismissRequest = { pendingDelete = null },
            content = {
                Column {
                    Text("确定删除 ${entry.file.name}？此操作不可撤销。")
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(
                            text = "取消",
                            onClick = { pendingDelete = null },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "删除",
                            onClick = {
                                viewModel.deleteImage(entry)
                                if (uiState.viewing?.file == entry.file) viewModel.closeViewer()
                                pendingDelete = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
    }

    // ── 清空确认 ──
    if (confirmClear) {
        WindowDialog(
            show = true,
            title = "清空图库",
            onDismissRequest = { confirmClear = false },
            content = {
                Column {
                    Text("将删除全部 ${uiState.gallery.size} 张图像，此操作不可撤销。")
                    Spacer(Modifier.height(12.dp))
                    Row {
                        TextButton(
                            text = "取消",
                            onClick = { confirmClear = false },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "清空",
                            onClick = {
                                viewModel.clearGallery()
                                confirmClear = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
    }
}

/** 网格中的一格：缩略图（右上角带删除）+ 模式名 + 时间 + 增强标记。 */
@Composable
private fun GalleryTile(
    entry: SstvImageStore.Entry,
    thumbnail: android.graphics.Bitmap?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            // 缩略图单独占一行：删除按钮**不再盖在图上**（以前右上角的「删除」
            // 会压住画面内容，看图时很碍眼），改到下方信息行里
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
                        "载入中",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onBackground,
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
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onBackground,
                    )
                    Text(
                        text = STAMP_FORMAT.format(Date(entry.timestamp)),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.primary,
                    )
                }
                if (onDelete != null) {
                    TextButton(text = "删除", onClick = onDelete)
                }
            }
        }
    }
}

/**
 * 全屏查看的分页容器：左右滑动切换图库里的相邻图像。
 *
 * 为什么用外层 [HorizontalPager] 而**不是让每页自己去记住位置**：
 * 一次只解码一张大图（[SstvViewModel.openViewer] 只保留当前张的位图），
 * 因此翻页时必须回调 [onSelect] 让 ViewModel 换图；pager 的当前页由
 * [current] 在 [SstvImageStore.Entry] 列表里的下标决定，删除图片导致列表
 * 变化时会自动收敛到有效范围。
 *
 * @param gallery 图库全部条目（决定可翻页范围与左右顺序）
 * @param current 当前正在查看的条目
 * @param bitmap 当前条目的解码大图（仅当前张有）
 * @param onSelect 翻到某页时回调（交给 ViewModel 换图）
 */
@Composable
private fun GalleryPager(
    gallery: List<SstvImageStore.Entry>,
    current: SstvImageStore.Entry,
    bitmap: android.graphics.Bitmap?,
    onSelect: (SstvImageStore.Entry) -> Unit,
    onClose: () -> Unit,
    onDelete: (SstvImageStore.Entry) -> Unit,
    onExport: (SstvImageStore.Entry) -> Unit,
    onShare: (SstvImageStore.Entry) -> Unit,
) {
    // 当前条目在列表里的下标；列表为空或已不存在时退回 0，避免越界
    val currentIndex = gallery.indexOfFirst { it.file.name == current.file.name }
        .coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { gallery.size },
    )
    val scope = rememberCoroutineScope()

    // 外部换图（例如删除后自动切到下一张）时，把 pager 同步过去 —— 单向由状态驱动
    LaunchedEffect(currentIndex) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != currentIndex) {
            pagerState.scrollToPage(currentIndex)
        }
    }
    // 用户滑动后回调 ViewModel 换图（大图解码在协程里，UI 不阻塞）
    LaunchedEffect(pagerState.currentPage) {
        gallery.getOrNull(pagerState.currentPage)?.let { page ->
            if (page.file.name != current.file.name) onSelect(page)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // 拿到焦点后响应滑动手势；keys 用文件名，避免删除后错位复用
        key = { index -> gallery.getOrNull(index)?.file?.name ?: index },
    ) { index ->
        val entry = gallery.getOrNull(index) ?: return@HorizontalPager
        // 只有当前页才有大图：其余页显示「滑动中」占位，避免多张大图同时驻留内存
        GalleryViewer(
            entry = entry,
            bitmap = if (index == currentIndex) bitmap else null,
            // 非当前页提示要等翻到才解，不用「正在解码」以免误导
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
private fun GalleryViewer(
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
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.modeName.ifBlank { "未知模式" } + if (entry.enhanced) "（增强）" else "",
                        style = MiuixTheme.textStyles.title4,
                        color = Color.White,
                    )
                    Text(
                        "${STAMP_FORMAT.format(Date(entry.timestamp))} · " +
                            "${formatBytes(entry.sizeBytes)} · $position",
                        style = MiuixTheme.textStyles.footnote1,
                        color = Color(0xFFB0B0B0),
                    )
                }
                // 左右切换：与滑动等价，给不方便滑动的场景留一条路
                if (onPrev != null) {
                    TextButton(text = "上一张", onClick = onPrev)
                }
                if (onNext != null) {
                    TextButton(text = "下一张", onClick = onNext)
                }
                TextButton(text = "关闭", onClick = onClose)
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
                    Text(loadingText, style = MiuixTheme.textStyles.body2, color = Color.White)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("分享")
                }
                Button(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导出到相册")
                }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("删除")
                }
            }
        }
    }
}

/** 人类可读的字节数。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val STAMP_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
