package com.example.hamkit.permission

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PermissionManager(context: Context) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(readState())

    val state: StateFlow<PermissionState> = _state.asStateFlow()

    fun refresh() {
        _state.value = readState()
    }

    /**
     * 定位运行时权限：ACCESS_FINE_LOCATION（核心业务依赖，首选授权）。
     * 与 [PermissionState.location] 口径一致：FINE 或 COARSE 任一授权即视为已授权。
     */
    fun locationRuntimePermission(): String = Manifest.permission.ACCESS_FINE_LOCATION

    /**
     * 麦克风运行时权限（FT8 解码等可选功能）。
     */
    fun microphoneRuntimePermission(): String = Manifest.permission.RECORD_AUDIO

    /**
     * 通知运行时权限（Android 13+）。
     */
    fun notificationRuntimePermission(): String = Manifest.permission.POST_NOTIFICATIONS

    /**
     * 是否应当用运行时请求而非跳转系统设置来授予通知权限。
     *
     * Android 13+ 用 POST_NOTIFICATIONS 运行时请求；
     * Android 13- 没有运行时权限概念，通知默认开启，被用户手动关闭时跳系统设置。
     */
    fun shouldRequestNotificationRuntime(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 跳转系统设置开启通知权限。
     */
    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        }

    /**
     * 跳转系统设置开启"精确闹钟"权限（Android 12+）。
     * 过境提醒用 setExactAndAllowWhileIdle，未授权会回退到非精确闹钟导致时间偏差大。
     */
    fun exactAlarmSettingsIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.fromParts("package", appContext.packageName, null)
        }

    fun locationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", appContext.packageName, null)
        }

    private fun readState() = PermissionState(
        // 与 LocationHelper.hasPermission() 口径一致：FINE 或 COARSE 任一授权即视为已授权
        location = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        // Android 13+ 必须运行时检查 POST_NOTIFICATIONS；13- 默认授予，视为已通过
        notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        },
        // Android 12+ 必须显式授权 SCHEDULE_EXACT_ALARM；12- 系统无条件支持
        exactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true
        },
        microphone = hasPermission(Manifest.permission.RECORD_AUDIO),
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PermissionChecker.PERMISSION_GRANTED
}
