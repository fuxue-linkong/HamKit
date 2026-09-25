package com.example.hamkit.data.satellite

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 卫星分类配置持久化存储。
 *
 * 存储三部分内容（JSON 序列化进 SharedPreferences，与 [SatelliteCacheStore] 同范式）：
 * 1. [SatelliteCategory] 列表
 * 2. 卫星归属关系（NORAD 编号 → 分类 id 集合）
 * 3. 卫星级提醒开关（NORAD 编号集合，由原「收藏」语义平移而来）
 *
 * 另有「历史收藏是否已迁移」标记，保证迁移只执行一次且失败可重试。
 *
 * 说明：本存储**不复用** `radio_area_favorites`，避免与提醒链路互相干扰。
 */
class SatelliteCategoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取完整配置。
     *
     * 容错约定：
     * - 键值类型异常（例如云备份恢复到不同构建、或被写入非 String）一律退化为空，**不抛异常**；
     * - 归属关系中指向不存在分类的「悬空引用」会被清理，
     *   避免出现「计数与排序算作已分类、却不显示任何分类芯片」且无法清除的状态。
     *   注意：分类列表为空时**不做清理**，否则分类 JSON 解析失败会把归属数据一并抹掉。
     */
    fun loadConfig(): SatelliteCategoryConfig {
        val categories = loadCategories()
        val rawMembership = loadMembership()
        val membership = if (categories.isEmpty()) {
            rawMembership
        } else {
            val validIds = categories.mapTo(HashSet()) { it.id }
            rawMembership
                .mapValues { (_, ids) -> ids.filterTo(HashSet()) { it in validIds } }
                .filterValues { it.isNotEmpty() }
        }
        return SatelliteCategoryConfig(
            categories = categories,
            membership = membership,
            reminderFlags = loadReminderFlags(),
        )
    }

    /**
     * 在**进程级锁**内执行「读 → 改 → 写」。
     *
     * 为什么需要：分类配置会被前台 `MainViewModel` 与后台 `ReminderRefreshWorker`
     * 同时写入（升级后两者常在数十秒内先后运行）。若各自「读一份内存快照 → 全量覆盖写」，
     * 后写者会静默覆盖先写者的修改（丢更新）。本方法每次都在锁内重新读取磁盘最新值，
     * 因此也能把 Worker 已落盘的迁移结果并回内存状态。
     *
     * @return 变换后的配置（即磁盘上的最新值）。
     */
    fun mutate(
        transform: (SatelliteCategoryConfig) -> SatelliteCategoryConfig
    ): SatelliteCategoryConfig = synchronized(CONFIG_LOCK) {
        val current = loadConfig()
        val updated = transform(current)
        if (updated != current) saveConfig(updated)
        updated
    }

    /**
     * 读取配置；若历史「收藏」迁移尚未执行，则先执行一次迁移并落盘。
     *
     * 由 `MainViewModel` 与后台 `ReminderRefreshWorker` **共用**，保证：
     * 1. 两处对「哪些卫星需要提醒」的判断始终一致；
     * 2. 后台任务不会在迁移执行之前因提醒开关为空而漏更新提醒。
     *
     * 迁移全程持有 [CONFIG_LOCK]，与 [mutate] 互斥，避免首次迁移覆盖用户刚做的编辑。
     *
     * @param legacyFavorites 历史「收藏」集合，由调用方从 `FavoriteSatellitesStore` 读取，
     *        避免本存储反向依赖旧存储。
     */
    fun loadConfigWithLegacyMigration(legacyFavorites: Set<Int>): SatelliteCategoryConfig =
        synchronized(CONFIG_LOCK) {
            val stored = loadConfig()
            if (isLegacyMigrationDone()) {
                stored
            } else {
                try {
                    val migrated = stored.migrateLegacyFavorites(legacyFavorites)
                    // 无实际变化时不回写，避免把「按容错规则降级读出」的结果固化成新的磁盘状态
                    if (migrated != stored) saveConfig(migrated)
                    markLegacyMigrationDone()
                    migrated
                } catch (_: Throwable) {
                    // 迁移失败：不标记、不覆盖，下次调用重试
                    stored
                }
            }
        }

    /**
     * 读取分类列表。
     */
    fun loadCategories(): List<SatelliteCategory> {
        // 使用 prefs.all + as? 而非 getString：键值类型异常时退化为空，而不是抛 ClassCastException
        val json = prefs.all[KEY_CATEGORIES] as? String ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                // 显式 JSON null 在 org.json 下会被 optString 读成字面量 "null"，需排除
                if (obj.isNull(KEY_CATEGORY_ID)) return@mapNotNull null
                val id = obj.optString(KEY_CATEGORY_ID, "").trim()
                if (id.isEmpty()) return@mapNotNull null
                SatelliteCategory(
                    id = id,
                    name = obj.optString(KEY_CATEGORY_NAME, ""),
                    sortOrder = obj.optInt(KEY_CATEGORY_SORT_ORDER, 0),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取卫星归属关系。
     */
    fun loadMembership(): Map<Int, Set<String>> {
        val json = prefs.all[KEY_MEMBERSHIP] as? String ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    val catalogNumber = key.toIntOrNull() ?: return@forEach
                    // NORAD 编号必须为正，与导入校验保持一致
                    if (!isValidCatalogNumber(catalogNumber)) return@forEach
                    val ids = obj.optJSONArray(key) ?: return@forEach
                    val set = buildSet {
                        for (i in 0 until ids.length()) {
                            if (ids.isNull(i)) continue
                            val id = ids.optString(i, "").trim()
                            if (id.isNotEmpty()) add(id)
                        }
                    }
                    if (set.isNotEmpty()) put(catalogNumber, set)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 读取卫星级提醒开关集合。
     */
    fun loadReminderFlags(): Set<Int> {
        val json = prefs.all[KEY_REMINDERS] as? String ?: return emptySet()
        return try {
            val array = JSONArray(json)
            buildSet {
                for (i in 0 until array.length()) {
                    val value = array.optInt(i, -1)
                    if (isValidCatalogNumber(value)) add(value)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * 写入完整配置。
     */
    fun saveConfig(config: SatelliteCategoryConfig) {
        prefs.edit()
            .putString(KEY_CATEGORIES, encodeCategories(config.categories))
            .putString(KEY_MEMBERSHIP, encodeMembership(config.membership))
            .putString(KEY_REMINDERS, encodeReminderFlags(config.reminderFlags))
            .apply()
    }

    /**
     * 写入分类列表。
     */
    fun saveCategories(categories: List<SatelliteCategory>) {
        prefs.edit().putString(KEY_CATEGORIES, encodeCategories(categories)).apply()
    }

    /**
     * 写入卫星归属关系。
     */
    fun saveMembership(membership: Map<Int, Set<String>>) {
        prefs.edit().putString(KEY_MEMBERSHIP, encodeMembership(membership)).apply()
    }

    /**
     * 写入卫星级提醒开关。
     */
    fun saveReminderFlags(flags: Set<Int>) {
        prefs.edit().putString(KEY_REMINDERS, encodeReminderFlags(flags)).apply()
    }

    /**
     * 历史「收藏」数据是否已完成迁移。
     *
     * 同样使用 `prefs.all + as?` 容错：键值类型异常时按「未迁移」处理（迁移本身是幂等的）。
     */
    fun isLegacyMigrationDone(): Boolean = prefs.all[KEY_MIGRATED_V1] as? Boolean ?: false

    /**
     * 标记历史「收藏」数据迁移已完成。
     *
     * 仅在迁移**成功写入**后调用；失败时不标记，下次启动重试。
     */
    fun markLegacyMigrationDone() {
        prefs.edit().putBoolean(KEY_MIGRATED_V1, true).apply()
    }

    /**
     * 清空全部分类配置**以及迁移标记**。
     *
     * ⚠️ 仅用于测试与「彻底重置」场景。由于迁移标记一并清除，下次读取会重新从
     * `radio_area_favorites` 迁移，把内置分类与提醒开关**恢复回来**。
     * 因此**不要**把本方法直接接到面向用户的「重置分类」入口；
     * 那种场景应只清 categories/membership/reminders，保留迁移标记。
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_CATEGORIES)
            .remove(KEY_MEMBERSHIP)
            .remove(KEY_REMINDERS)
            .remove(KEY_MIGRATED_V1)
            .apply()
    }

    private fun encodeCategories(categories: List<SatelliteCategory>): String {
        val array = JSONArray()
        categories.forEach { category ->
            val obj = JSONObject()
            obj.put(KEY_CATEGORY_ID, category.id)
            obj.put(KEY_CATEGORY_NAME, category.name)
            obj.put(KEY_CATEGORY_SORT_ORDER, category.sortOrder)
            array.put(obj)
        }
        return array.toString()
    }

    private fun encodeMembership(membership: Map<Int, Set<String>>): String {
        val obj = JSONObject()
        membership.forEach { (catalogNumber, ids) ->
            if (ids.isEmpty()) return@forEach
            val array = JSONArray()
            ids.forEach { array.put(it) }
            obj.put(catalogNumber.toString(), array)
        }
        return obj.toString()
    }

    private fun encodeReminderFlags(flags: Set<Int>): String {
        val array = JSONArray()
        flags.forEach { array.put(it) }
        return array.toString()
    }

    companion object {
        /**
         * 进程级配置锁：所有实例共享，保证前台与后台 Worker 的
         * 「读-改-写」以及历史迁移不会互相覆盖。
         */
        private val CONFIG_LOCK = Any()

        private const val PREFS_NAME = "radio_area_satellite_categories"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_MEMBERSHIP = "membership"
        private const val KEY_REMINDERS = "reminder_flags"
        private const val KEY_MIGRATED_V1 = "legacy_favorites_migrated_v1"

        private const val KEY_CATEGORY_ID = "id"
        private const val KEY_CATEGORY_NAME = "name"
        private const val KEY_CATEGORY_SORT_ORDER = "sortOrder"
    }
}
