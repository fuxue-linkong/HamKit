package com.example.hamkit.shared

/**
 * 卫星分类纯逻辑层 —— 从 Android `SatelliteCategory.kt` 移植进 KMP 共享内核。
 *
 * 分类负责「组织 / 浏览 / 筛选」；过境提醒由独立的卫星级提醒开关（[SatelliteCategoryConfig.reminderFlags]）
 * 控制，二者互不影响 —— 把卫星加入分类不会创建或删除任何提醒。
 *
 * 持久化 JSON 由各端实现：Android 复用现有 SatelliteCategoryStore，
 * iOS 用 UserDefaults 存同构 schema 的 JSON（Swift Codable）。
 */
data class SatelliteCategory(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
) {
    /** 是否为内置分类。内置分类不可删除（避免迁移数据失去归属）。 */
    val isBuiltin: Boolean get() = id.startsWith(BUILTIN_ID_PREFIX)
}

/** 内置分类 id 前缀。 */
const val BUILTIN_ID_PREFIX = "builtin:"

/** 内置「我的关注」分类 id：承接历史收藏数据，不可删除。 */
const val BUILTIN_STARRED_CATEGORY_ID = "builtin:starred"

/** 内置「我的关注」分类的默认名称。 */
const val BUILTIN_STARRED_CATEGORY_NAME = "我的关注"

/**
 * 卫星分类配置整体快照。
 *
 * - [categories]：分类定义
 * - [membership]：NORAD 编号 → 所属分类 id 集合（一颗卫星可属于多个分类）
 * - [reminderFlags]：需要过境提醒的 NORAD 编号集合
 */
data class SatelliteCategoryConfig(
    val categories: List<SatelliteCategory> = emptyList(),
    val membership: Map<Int, Set<String>> = emptyMap(),
    val reminderFlags: Set<Int> = emptySet(),
) {
    /** 属于任一分类的卫星编号集合（用于列表排序与高亮）。 */
    val categorizedCatalogNumbers: Set<Int> get() = membership.keys

    /** 某颗卫星所属的分类 id 集合。 */
    fun categoryIdsOf(catalogNumber: Int): Set<String> = membership[catalogNumber].orEmpty()

    /** 某个分类下的卫星数量。 */
    fun satelliteCountOf(categoryId: String): Int =
        membership.values.count { categoryId in it }

    /** 按 [SatelliteCategory.sortOrder] 排序后的分类列表。 */
    fun sortedCategories(): List<SatelliteCategory> = categories.sortedBy { it.sortOrder }

    /** 按 id 查找分类。 */
    fun categoryById(categoryId: String): SatelliteCategory? =
        categories.firstOrNull { it.id == categoryId }
}

/** NORAD 编号是否合法（统一校验入口）。 */
fun isValidCatalogNumber(catalogNumber: Int): Boolean = catalogNumber > 0

/**
 * 新建分类并追加到末尾。名称空白或 id 已存在时不新增。
 *
 * @return 新配置与新分类（未新增时为原配置与已有/待建分类）。
 */
fun SatelliteCategoryConfig.addCategory(
    name: String,
    id: String,
): Pair<SatelliteCategoryConfig, SatelliteCategory> {
    val trimmed = name.trim()
    categories.firstOrNull { it.id == id }?.let { existing ->
        return this to existing
    }
    if (trimmed.isEmpty()) {
        return this to SatelliteCategory(id = id, name = trimmed)
    }
    val nextOrder = (categories.maxOfOrNull { it.sortOrder } ?: -1) + 1
    val category = SatelliteCategory(id = id, name = trimmed, sortOrder = nextOrder)
    return copy(categories = categories + category) to category
}

/** 重命名分类。名称为空白或分类不存在时返回原配置。 */
fun SatelliteCategoryConfig.renameCategory(categoryId: String, newName: String): SatelliteCategoryConfig {
    val trimmed = newName.trim()
    if (trimmed.isEmpty()) return this
    if (categories.none { it.id == categoryId }) return this
    return copy(categories = categories.map { if (it.id == categoryId) it.copy(name = trimmed) else it })
}

/**
 * 删除分类，同时移除所有卫星对它的归属。
 * 内置分类不可删除。不影响 [SatelliteCategoryConfig.reminderFlags]。
 */
fun SatelliteCategoryConfig.removeCategory(categoryId: String): SatelliteCategoryConfig {
    val target = categories.firstOrNull { it.id == categoryId } ?: return this
    if (target.isBuiltin) return this
    val cleanedMembership = membership
        .mapValues { (_, ids) -> ids - categoryId }
        .filterValues { it.isNotEmpty() }
    return copy(
        categories = categories.filterNot { it.id == categoryId },
        membership = cleanedMembership,
    )
}

/** 切换某颗卫星在某个分类中的归属。 */
fun SatelliteCategoryConfig.toggleAssignment(
    catalogNumber: Int,
    categoryId: String,
): SatelliteCategoryConfig {
    if (categories.none { it.id == categoryId }) return this
    val current = membership[catalogNumber].orEmpty()
    val updated = if (categoryId in current) current - categoryId else current + categoryId
    val nextMembership = if (updated.isEmpty()) {
        membership - catalogNumber
    } else {
        membership + (catalogNumber to updated)
    }
    return copy(membership = nextMembership)
}

/** 移除某颗卫星的全部分类归属（不影响提醒开关）。 */
fun SatelliteCategoryConfig.clearAssignments(catalogNumber: Int): SatelliteCategoryConfig =
    copy(membership = membership - catalogNumber)

/** 切换某颗卫星的过境提醒开关（不影响分类归属）。 */
fun SatelliteCategoryConfig.toggleReminder(catalogNumber: Int): SatelliteCategoryConfig {
    val updated = if (catalogNumber in reminderFlags) {
        reminderFlags - catalogNumber
    } else {
        reminderFlags + catalogNumber
    }
    return copy(reminderFlags = updated)
}

/**
 * 把历史「收藏」数据迁移进内置分类，并同步写入提醒开关（幂等）。
 * 无历史收藏时不做任何改动（全新安装不会凭空多出一个空分类）。
 */
fun SatelliteCategoryConfig.migrateLegacyFavorites(
    legacyFavorites: Set<Int>,
): SatelliteCategoryConfig {
    val validFavorites = legacyFavorites.filterTo(HashSet()) { it > 0 }
    if (validFavorites.isEmpty()) return this

    val existing = categories.firstOrNull { it.id == BUILTIN_STARRED_CATEGORY_ID }
    val base = if (existing != null) {
        this
    } else {
        val starred = SatelliteCategory(
            id = BUILTIN_STARRED_CATEGORY_ID,
            name = BUILTIN_STARRED_CATEGORY_NAME,
            // -1 保证内置分类稳定排在最前，不依赖排序算法的稳定性
            sortOrder = -1,
        )
        copy(categories = listOf(starred) + categories)
    }

    val mergedMembership = base.membership.toMutableMap()
    validFavorites.forEach { catalogNumber ->
        mergedMembership[catalogNumber] =
            mergedMembership[catalogNumber].orEmpty() + BUILTIN_STARRED_CATEGORY_ID
    }
    return base.copy(
        membership = mergedMembership,
        reminderFlags = base.reminderFlags + validFavorites,
    )
}
