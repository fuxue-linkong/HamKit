package com.example.hamkit.ui.screen.colorpalette

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.example.hamkit.R
import com.example.hamkit.HamKitApplication
import com.example.hamkit.data.LandscapeImageStore
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.theme.ColorMode
import com.example.hamkit.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

private const val IMPORT_LOG_TAG = "BgImport"

/**
 * 图库返回后的返回键吞掉窗口（毫秒）。
 *
 * 覆盖「系统图库已 finish、本页刚重新 START/RESUME」的竞态：这段时间内
 * ROM 仍可能把返回事件派发到 Activity 级的 RootBackHandler，导致设置页被
 * 无提示 pop 掉。窗口只在图库返回后开启一次，不影响之后的正常返回。
 */
private const val PICKER_RESUME_GRACE_MS = 1200L

/**
 * 选图成功后、启动裁剪页前的额外宽限（毫秒）。
 *
 * 若在本页尚未真正 RESUMED 时就启动裁剪 Activity，部分 ROM 会把裁剪页的
 * setResult 送错窗口（结果被静默丢弃，表现为「点裁剪确认后导入无反应」）。
 * 因此先等本页回到前台并稳定一小段时间，再进入裁剪。
 */
private const val CROP_LAUNCH_RESUME_DELAY_MS = 300L

@Composable
fun ColorPaletteScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val viewModel = appViewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val imageStore = remember { LandscapeImageStore(context.applicationContext) }
    val mainViewModel = LocalMainViewModel.current
    val currentPaletteStyle = try {
        PaletteStyle.valueOf(uiState.colorStyle)
    } catch (_: Exception) {
        PaletteStyle.TonalSpot
    }
    val currentColorSpec = try {
        ColorSpec.SpecVersion.valueOf(uiState.colorSpec)
    } catch (_: Exception) {
        ColorSpec.SpecVersion.Default
    }
    // 主题模式在 SettingsRepository 中以 Int 存储，需转回 ColorMode 枚举
    val currentColorMode = ColorMode.fromValue(uiState.themeMode)

    // 导入成功/失败后的统一收尾：更新状态并刷新首页时间卡背景
    val onSaved: () -> Unit = {
        // 存储实际文件路径而非源 URI（源 URI 进程重启后可能失效）
        viewModel.setCustomBackgroundUri(imageStore.customImageFile.absolutePath)
        mainViewModel.refreshLandscapeImage()
    }

    val onFailed: (String) -> Unit = { reason ->
        Log.e(IMPORT_LOG_TAG, "背景导入失败：$reason")
        Toast.makeText(
            context,
            context.getString(R.string.timecard_background_save_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    // ── 系统图库（Photopicker）返回栈保护 ───────────────────────────
    //
    // 问题现象：在图库里点开大图查看后按返回，本应用的设置页被一起 pop 掉。
    // 根因：MainActivity 的 RootBackHandler 注册在 Activity 级
    // OnBackPressedDispatcher 上且 enabled = true，只要返回事件被派发到
    // Activity 就会出栈；图库在前台期间/返回竞态期间，本应用仍可能收到该事件，
    // 页面就在用户看不见的情况下退出一层。
    //
    // 对策：
    //  1) 打开图库前登记 pickerInFlight；图库返回使本页重新 START 时开启
    //     pickerCovered 窗口（PICKER_RESUME_GRACE_MS）；
    //  2) 本页注册一个最内层的 BackHandler 吞掉这段时间内的返回事件，
    //     只拦这一小段，不影响之后的正常返回；
    //  3) 选图成功后把返回栈收敛为 [Main, ColorPalette]，保证最多 pop 一层，
    //     不会直接把页面关掉；
    //  4) 选图后等本页 RESUMED 再启动裁剪页（见 CROP_LAUNCH_RESUME_DELAY_MS 注释）。
    val lifecycleOwner = LocalLifecycleOwner.current
    var pickerInFlight by remember { mutableStateOf(false) }
    var pickerCovered by remember { mutableStateOf(false) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var cropLaunchAfter by remember { mutableLongStateOf(0L) }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        pickerInFlight = false
        if (uri != null) {
            Log.i(IMPORT_LOG_TAG, "图库已选图：$uri")
            // 返回栈收敛：确保 [Main, ColorPalette]，防止后续返回把页面直接关掉
            navigator.ensureMinimumBackStack(Route.Main, Route.ColorPalette)
            pendingCropUri = uri
            // 以 RESUMED 时刻为基准，再加上裁剪启动宽限
            cropLaunchAfter = SystemClock.uptimeMillis() + CROP_LAUNCH_RESUME_DELAY_MS
        } else {
            Log.i(IMPORT_LOG_TAG, "图库取消选图")
        }
    }

    // 图库返回使本页重新 START：开启返回键吞掉窗口
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && pickerInFlight) {
                pickerCovered = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 吞掉窗口到期后自动关闭
    LaunchedEffect(pickerCovered) {
        if (pickerCovered) {
            delay(PICKER_RESUME_GRACE_MS)
            pickerCovered = false
        }
    }

    // 图库打开中 / 返回竞态窗口内：吞掉返回事件（本 handler 注册时机最晚，
    // 在 OnBackPressedDispatcher 回调栈中位于 RootBackHandler 内层，优先触发）
    BackHandler(enabled = pickerInFlight || pickerCovered || pendingCropUri != null) {
        Log.i(IMPORT_LOG_TAG, "忽略返回事件：系统图库返回窗口内")
    }

    val actions = ColorPaletteScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSetThemeMode = viewModel::setThemeMode,
        onSetMiuixMonet = viewModel::setMiuixMonet,
        onSetKeyColor = viewModel::setKeyColor,
        onSetColorMode = viewModel::setColorMode,
        onSetColorStyle = viewModel::setColorStyle,
        onSetColorSpec = viewModel::setColorSpec,
        onSetEnableBlur = viewModel::setEnableBlur,
        onSetEnableFloatingBottomBar = viewModel::setEnableFloatingBottomBar,
        onSetEnableFloatingBottomBarBlur = viewModel::setEnableFloatingBottomBarBlur,
        onSetEnablePredictiveBack = {
            viewModel.setEnablePredictiveBack(it)
            HamKitApplication.setEnableOnBackInvokedCallback(context.applicationInfo, it)
            activity?.recreate()
        },
        onSetPageScale = viewModel::setPageScale,
        // 首选路径：直接用裁剪结果位图保存，绕开 MediaStore 临时授权失效问题
        onSetCustomBackgroundBitmap = { bitmap: Bitmap ->
            if (imageStore.saveCustomImage(bitmap)) {
                onSaved()
            } else {
                onFailed("裁剪结果位图保存失败")
            }
            bitmap.recycle()
        },
        // 兼容路径：裁剪页只回传 uri 时（如 Activity 重建）仍按 uri 读取并保存
        onSetCustomBackground = { uri: Uri ->
            Log.i(IMPORT_LOG_TAG, "背景导入走 uri 路径：${uri.scheme}:$uri")
            if (imageStore.saveCustomImage(uri, context)) {
                onSaved()
            } else {
                onFailed("uri 读取保存失败：${uri.scheme}:$uri")
            }
        },
        // 打开系统图库前登记 in-flight，用于吞掉图库期间的返回事件
        onOpenImagePicker = {
            pickerInFlight = true
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onClearCustomBackground = {
            imageStore.clearCustomImage()
            viewModel.clearCustomBackgroundUri()
            mainViewModel.refreshLandscapeImage()
        },
    )

    // ── 裁剪结果回调 ────────────────────────────────────────────────
    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        // 裁剪结果优先使用内存位图，避免 uriContent 指向相册 uri 时授权失效导致保存失败
        val cropped = result?.takeIf { it.isSuccessful }?.bitmap
        if (cropped != null) {
            actions.onSetCustomBackgroundBitmap(cropped)
        } else {
            val uri = result?.takeIf { it.isSuccessful }?.uriContent
            if (uri != null) {
                actions.onSetCustomBackground(uri)
            } else {
                Log.w(IMPORT_LOG_TAG, "裁剪结果为空或失败：successful=${result?.isSuccessful}, error=${result?.error}")
            }
        }
    }

    // 等本页回到前台并稳定后，再启动裁剪页（避免 setResult 送错窗口）
    LaunchedEffect(pendingCropUri, cropLaunchAfter) {
        val uri = pendingCropUri ?: return@LaunchedEffect
        val remain = cropLaunchAfter - SystemClock.uptimeMillis()
        if (remain > 0) {
            delay(remain)
        }
        pendingCropUri = null
        cropLauncher.launch(
            CropImageContractOptions(
                uri = uri,
                cropImageOptions = CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    fixAspectRatio = true,
                    aspectRatioX = 16,
                    aspectRatioY = 9,
                )
            )
        )
    }

    // 组装 UI 状态（重构时漏掉了这行，导致两个子屏幕收到未定义的 state）
    val state = ColorPaletteUiState(
        uiState = uiState,
        currentColorMode = currentColorMode,
        currentPaletteStyle = currentPaletteStyle,
        currentColorSpec = currentColorSpec,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> ColorPaletteScreenMiuix(state, actions)
        UiMode.Material -> ColorPaletteScreenMaterial(state, actions)
    }
}
