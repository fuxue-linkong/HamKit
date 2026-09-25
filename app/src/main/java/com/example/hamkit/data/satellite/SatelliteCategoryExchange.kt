package com.example.hamkit.data.satellite

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 卫星分类配置的导入 / 导出。
 *
 * 导出为带 schema 标记的 JSON 文件，便于用户互相分享分类设置。
 * 导入采用**合并**策略：同 id 分类更新名称，新 id 分类追加，
 * 归属关系与提醒开关取并集，绝不删除用户既有数据。
 *
 * 本对象全部为纯函数（仅依赖 org.json），便于单元测试。
 */
object SatelliteCategoryExchange {

    /** 交换文件 schema 标识。导入时必须完全匹配，避免误导入无关 JSON。 */
    const val SCHEMA = "hamkit.satellite.categories"

    /** 当前支持的交换格式版本。导入时接受 `1..VERSION`。 */
    const val VERSION = 1

    /** 导入文件长度上限（字符）。防御性限制，避免用户误选超大文件导致 OOM。 */
    const val MAX_JSON_CHARS = 8 * 1024 * 1024

    /** 单个文件允许导入的分类数量上限。防止异常文件把分类弹窗撑成上千行。 */
    const val MAX_CATEGORIES = 200

    private const val KEY_SCHEMA = "schema"
    private const val KEY_VERSION = "version"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_MEMBERSHIP = "membership"
    private const val KEY_REMINDER_FLAGS = "reminderFlags"
    private const val KEY_SATELLITE_NAMES = "satelliteNames"

    private const val KEY_CATEGORY_ID = "id"
    private const val KEY_CATEGORY_NAME = "name"
    private const val KEY_CATEGORY_SORT_ORDER = "sortOrder"

    /**
     * 序列化为可分享的 JSON 文本。
     *
     * @param satelliteNames NORAD 编号 → 卫星名称，仅用于提升文件可读性；
     *        导入时会被忽略（以 NORAD 编号为准）。
     */
    fun export(
        config: SatelliteCategoryConfig,
        satelliteNames: Map<Int, String> = emptyMap(),
        appVersion: String = "",
        exportedAt: Instant = Instant.now(),
    ): String {
        val root = JSONObject()
        root.put(KEY_SCHEMA, SCHEMA)
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_EXPORTED_AT, exportedAt.toString())
        root.put(KEY_APP_VERSION, appVersion)

        val categoriesArray = JSONArray()
        config.sortedCategories().forEach { category ->
            val obj = JSONObject()
            obj.put(KEY_CATEGORY_ID, category.id)
            obj.put(KEY_CATEGORY_NAME, category.name)
            obj.put(KEY_CATEGORY_SORT_ORDER, category.sortOrder)
            categoriesArray.put(obj)
        }
        root.put(KEY_CATEGORIES, categoriesArray)

        val membershipObj = JSONObject()
        config.membership.forEach { (catalogNumber, ids) ->
            if (ids.isEmpty()) return@forEach
            val array = JSONArray()
            ids.forEach { array.put(it) }
            membershipObj.put(catalogNumber.toString(), array)
        }
        root.put(KEY_MEMBERSHIP, membershipObj)

        val reminderArray = JSONArray()
        config.reminderFlags.forEach { reminderArray.put(it) }
        root.put(KEY_REMINDER_FLAGS, reminderArray)

        // 冗余可读性字段：仅包含确实出现在 membership 中的卫星
        val namesObj = JSONObject()
        config.membership.keys.sorted().forEach { catalogNumber ->
            val name = satelliteNames[catalogNumber]
            if (!name.isNullOrBlank()) namesObj.put(catalogNumber.toString(), name)
        }
        root.put(KEY_SATELLITE_NAMES, namesObj)

        return root.toString(2)
    }

    /**
     * 解析并校验导入文件。
     *
     * 校验项：文件大小上限、JSON 合法性、[SCHEMA] 匹配、版本区间、分类 id 非空。
     * 任一项不满足返回 [Result.failure]，调用方据此提示用户且不修改现有配置。
     */
    fun parse(json: String): Result<SatelliteCategoryConfig> = runCatching {
        // SAF 允许用户选择任意文件，先用长度上限挡住超大文件，避免 OOM
        require(json.length <= MAX_JSON_CHARS) {
            "文件过大（${json.length} 字符，上限 $MAX_JSON_CHARS）"
        }

        val root = JSONObject(json)

        val schema = root.optString(KEY_SCHEMA, "")
        require(schema == SCHEMA) { "文件格式不支持：schema 应为 $SCHEMA，实际为 \"$schema\"" }

        // 版本必须是 JSON 整数：optInt 会把 1.9 截断为 1、"1" 也读成 1，
        // 用严格的类型检查避免接受语义不明的文件
        val versionValue = root.opt(KEY_VERSION)
        require(versionValue is Int && versionValue in 1..VERSION) {
            "文件版本不支持：$versionValue（当前最高支持 $VERSION）"
        }

        // categories 必须显式存在且为数组，避免接受结构不完整的文件
        require(root.has(KEY_CATEGORIES) && root.optJSONArray(KEY_CATEGORIES) != null) {
            "文件格式不支持：缺少 categories 数组"
        }
        val categoriesArray = root.optJSONArray(KEY_CATEGORIES)

        val categories = buildList {
            if (categoriesArray != null) {
                for (i in 0 until categoriesArray.length()) {
                    val obj = categoriesArray.optJSONObject(i) ?: continue
                    // 显式 JSON null 在 org.json 下会被读成字面量 "null"
                    if (obj.isNull(KEY_CATEGORY_ID)) continue
                    val id = obj.optString(KEY_CATEGORY_ID, "").trim()
                    if (id.isEmpty()) continue
                    if (size >= MAX_CATEGORIES) return@buildList
                    add(
                        SatelliteCategory(
                            id = id,
                            name = obj.optString(KEY_CATEGORY_NAME, "").trim().ifEmpty { id },
                            sortOrder = obj.optInt(KEY_CATEGORY_SORT_ORDER, 0),
                        )
                    )
                }
            }
        }

        val membershipObj = root.optJSONObject(KEY_MEMBERSHIP)
        val membership = buildMap {
            if (membershipObj != null) {
                membershipObj.keys().forEach { key ->
                    val catalogNumber = key.toIntOrNull() ?: return@forEach
                    if (!isValidCatalogNumber(catalogNumber)) return@forEach
                    val ids = membershipObj.optJSONArray(key) ?: return@forEach
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
        }

        val reminderArray = root.optJSONArray(KEY_REMINDER_FLAGS)
        val reminderFlags = buildSet {
            if (reminderArray != null) {
                for (i in 0 until reminderArray.length()) {
                    val value = reminderArray.optInt(i, -1)
                    if (isValidCatalogNumber(value)) add(value)
                }
            }
        }

        SatelliteCategoryConfig(
            categories = categories,
            membership = membership,
            reminderFlags = reminderFlags,
        )
    }

    /**
     * 将 [incoming] 合并进 [current]。
     *
     * 规则：
     * - 分类按 id 匹配：命中则更新名称（保留原排序），未命中则追加并排在末尾；
     * - 卫星归属取并集（仅保留指向已存在分类的归属，丢弃悬空引用）；
     * - 提醒开关取并集。
     */
    fun merge(
        current: SatelliteCategoryConfig,
        incoming: SatelliteCategoryConfig,
    ): CategoryMergeResult {
        var addedCategories = 0
        var updatedCategories = 0

        val mergedCategories = current.categories.toMutableList()
        var nextOrder = (mergedCategories.maxOfOrNull { it.sortOrder } ?: -1) + 1

        incoming.categories.forEach { incomingCategory ->
            val index = mergedCategories.indexOfFirst { it.id == incomingCategory.id }
            if (index >= 0) {
                val existing = mergedCategories[index]
                // 内置分类的名称由本地迁移决定，不接受导入文件改名
                if (!existing.isBuiltin && existing.name != incomingCategory.name) {
                    mergedCategories[index] = existing.copy(name = incomingCategory.name)
                    updatedCategories++
                }
            } else {
                // 内置分类只能由 migrateLegacyFavorites 建立：
                // 否则导入一份他人（已迁移）的配置，会在本机凭空种下一个**无法删除**的分类；
                // 同样也挡住了伪造 builtin: 前缀 id 的文件。
                if (incomingCategory.isBuiltin) return@forEach
                mergedCategories.add(incomingCategory.copy(sortOrder = nextOrder))
                nextOrder++
                addedCategories++
            }
        }

        val validIds = mergedCategories.map { it.id }.toSet()

        var addedAssignments = 0
        val mergedMembership = current.membership.toMutableMap()
        incoming.membership.forEach { (catalogNumber, incomingIds) ->
            val accepted = incomingIds.filter { it in validIds }
            if (accepted.isEmpty()) return@forEach
            val existing = mergedMembership[catalogNumber].orEmpty()
            val union = existing + accepted
            if (union.size != existing.size) {
                addedAssignments += union.size - existing.size
                mergedMembership[catalogNumber] = union
            }
        }

        val mergedReminders = current.reminderFlags + incoming.reminderFlags
        val addedReminderFlags = mergedReminders.size - current.reminderFlags.size

        return CategoryMergeResult(
            config = current.copy(
                categories = mergedCategories,
                membership = mergedMembership,
                reminderFlags = mergedReminders,
            ),
            addedCategories = addedCategories,
            updatedCategories = updatedCategories,
            addedAssignments = addedAssignments,
            addedReminderFlags = addedReminderFlags,
        )
    }

    /**
     * 默认导出文件名，形如 `HamKit_satellite_categories_2026-08-20.json`。
     */
    fun defaultFileName(instant: Instant = Instant.now()): String {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(instant)
        return "HamKit_satellite_categories_$date.json"
    }
}

/**
 * 合并结果与变更统计，用于向用户提示导入摘要。
 */
data class CategoryMergeResult(
    val config: SatelliteCategoryConfig,
    val addedCategories: Int,
    val updatedCategories: Int,
    val addedAssignments: Int,
    val addedReminderFlags: Int,
) {
    /** 是否产生了任何变化。 */
    val hasChanges: Boolean
        get() = addedCategories > 0 || updatedCategories > 0 ||
            addedAssignments > 0 || addedReminderFlags > 0
}
