package com.example.hamkit.data.reminder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 日程提醒持久化存储。
 *
 * 同时保存：
 * 1. [ReminderSettings] —— 用户偏好（开关、提前分钟数、重复模式、声音/振动）
 * 2. List<[ReminderItem]> —— 每颗已开启提醒卫星的下一次过境提醒项
 *
 * 进程重启后可恢复，提醒列表与「已开启过境提醒」的卫星一一对应。
 */
class ReminderStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取提醒设置。若从未写入则返回默认值（开启、提前 10 分钟、每次提醒、声音+振动）。
     */
    fun loadSettings(): ReminderSettings {
        return ReminderSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            leadMinutes = prefs.getInt(KEY_LEAD_MINUTES, 10),
            repeatMode = RepeatMode.fromName(prefs.getString(KEY_REPEAT_MODE, null)),
            soundEnabled = prefs.getBoolean(KEY_SOUND, true),
            vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, true)
        )
    }

    /**
     * 写入提醒设置。
     */
    fun saveSettings(settings: ReminderSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_LEAD_MINUTES, settings.leadMinutes)
            .putString(KEY_REPEAT_MODE, settings.repeatMode.name)
            .putBoolean(KEY_SOUND, settings.soundEnabled)
            .putBoolean(KEY_VIBRATION, settings.vibrationEnabled)
            .apply()
    }

    /**
     * 读取所有提醒项。返回的列表按 AOS 时间升序排列。
     */
    fun loadItems(): List<ReminderItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val modesArr = obj.optJSONArray(KEY_ITEM_MODES)
                ReminderItem(
                    catalogNumber = obj.optInt(KEY_ITEM_CATALOG, 0),
                    name = obj.optString(KEY_ITEM_NAME, ""),
                    aosTimeMillis = obj.optLong(KEY_ITEM_AOS, 0L),
                    losTimeMillis = obj.optLong(KEY_ITEM_LOS, 0L),
                    maxElevation = obj.optDouble(KEY_ITEM_MAX_EL, 0.0),
                    aosAzimuth = obj.optInt(KEY_ITEM_AOS_AZ, 0),
                    losAzimuth = obj.optInt(KEY_ITEM_LOS_AZ, 0),
                    modes = if (modesArr != null) {
                        (0 until modesArr.length()).map { modesArr.optString(it) }
                    } else emptyList(),
                    enabled = obj.optBoolean(KEY_ITEM_ENABLED, true)
                )
            }.sortedBy { it.aosTimeMillis }
        } catch (_: Exception) {
            // JSON 解析失败（如旧版本格式不兼容），返回空列表避免崩溃
            emptyList()
        }
    }

    /**
     * 写入完整提醒列表（覆盖）。
     */
    fun saveItems(items: List<ReminderItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put(KEY_ITEM_CATALOG, item.catalogNumber)
            obj.put(KEY_ITEM_NAME, item.name)
            obj.put(KEY_ITEM_AOS, item.aosTimeMillis)
            obj.put(KEY_ITEM_LOS, item.losTimeMillis)
            obj.put(KEY_ITEM_MAX_EL, item.maxElevation)
            obj.put(KEY_ITEM_AOS_AZ, item.aosAzimuth)
            obj.put(KEY_ITEM_LOS_AZ, item.losAzimuth)
            obj.put(KEY_ITEM_ENABLED, item.enabled)
            val modesArr = JSONArray()
            item.modes.forEach { modesArr.put(it) }
            obj.put(KEY_ITEM_MODES, modesArr)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    /**
     * 同步更新单条提醒项（按 catalogNumber 匹配）。若不存在则追加。
     */
    fun upsertItem(item: ReminderItem) {
        val current = loadItems().toMutableList()
        val idx = current.indexOfFirst { it.catalogNumber == item.catalogNumber }
        if (idx >= 0) current[idx] = item else current.add(item)
        saveItems(current)
    }

    /**
     * 删除指定卫星的提醒项。
     */
    fun removeItem(catalogNumber: Int) {
        val current = loadItems().filter { it.catalogNumber != catalogNumber }
        saveItems(current)
    }

    /**
     * 仅更新某颗卫星提醒的启用状态。返回更新后的列表。
     */
    fun setItemEnabled(catalogNumber: Int, enabled: Boolean): List<ReminderItem> {
        val current = loadItems().map {
            if (it.catalogNumber == catalogNumber) it.copy(enabled = enabled) else it
        }
        saveItems(current)
        return current
    }

    companion object {
        private const val PREFS_NAME = "radio_area_reminders"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LEAD_MINUTES = "lead_minutes"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_SOUND = "sound"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_ITEMS = "items"
        private const val KEY_ITEM_CATALOG = "catalog"
        private const val KEY_ITEM_NAME = "name"
        private const val KEY_ITEM_AOS = "aos"
        private const val KEY_ITEM_LOS = "los"
        private const val KEY_ITEM_MAX_EL = "max_el"
        private const val KEY_ITEM_AOS_AZ = "aos_az"
        private const val KEY_ITEM_LOS_AZ = "los_az"
        private const val KEY_ITEM_MODES = "modes"
        private const val KEY_ITEM_ENABLED = "enabled"
    }
}
