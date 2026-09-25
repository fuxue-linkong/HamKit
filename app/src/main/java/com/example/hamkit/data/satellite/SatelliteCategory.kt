package com.example.hamkit.data.satellite

import androidx.compose.runtime.Immutable

/**
 * 卫星自定义分类。
 *
 * 原「收藏」概念已并入分类体系：分类负责「组织 / 浏览 / 筛选」，
 * 过境提醒则由独立的卫星级提醒开关（[SatelliteCategoryConfig.reminderFlags]）控制，
 * 二者互不影响 —— 把卫星加入分类不会创建或删除任何提醒。
 *
 * @param id 稳定标识。用户分类使用 UUID；内置分类使用 [BUILTIN_ID_PREFIX] 前缀。
 * @param name 用户可见名称，可重命名。
 * @param sortOrder 展示排序，值越小越靠前。
 */
@Immutable
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
 * - [reminderFlags]：需要过境提醒的 NORAD 编号集合（由原「收藏」语义平移而来）
 */
@Immutable
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

// ══════════════════════════════════════════════════════════════════════════
// 纯函数操作：全部返回新快照，不涉及 Android API，便于单元测试。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 新建分类并追加到末尾。
 *
 * 防御性约定（本函数可能被测试或导入路径直接调用）：
 * - 名称为空白时**不新增**，原样返回；
 * - `id` 已存在时**不新增**，返回已存在的分类。
 *   重复 id 会让 [SatelliteCategoryConfig.categoryById]、[renameCategory]、
 *   [removeCategory] 行为歧义，必须在入口挡住。
 *
 * @param id 分类 id，默认生成 UUID；测试与导入可注入固定值。
 * @return 新配置与新分类（未新增时为原配置与已有/待建分类）。
 */
fun SatelliteCategoryConfig.addCategory(
    name: String,
    id: String = java.util.UUID.randomUUID().toString(),
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

/**
 * 重命名分类。名称为空白或分类不存在时返回原配置。
 */
fun SatelliteCategoryConfig.renameCategory(categoryId: String, newName: String): SatelliteCategoryConfig {
    val trimmed = newName.trim()
    if (trimmed.isEmpty()) return this
    if (categories.none { it.id == categoryId }) return this
    return copy(categories = categories.map { if (it.id == categoryId) it.copy(name = trimmed) else it })
}

/**
 * 删除分类，同时移除所有卫星对它的归属。
 *
 * 内置分类不可删除。**不影响 [SatelliteCategoryConfig.reminderFlags]** ——
 * 提醒是卫星级开关，与分类归属无关。
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

/**
 * 切换某颗卫星在某个分类中的归属。
 */
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

/**
 * 移除某颗卫星的全部分类归属（不影响提醒开关）。
 */
fun SatelliteCategoryConfig.clearAssignments(catalogNumber: Int): SatelliteCategoryConfig =
    copy(membership = membership - catalogNumber)

/**
 * 切换某颗卫星的过境提醒开关（不影响分类归属）。
 */
fun SatelliteCategoryConfig.toggleReminder(catalogNumber: Int): SatelliteCategoryConfig {
    val updated = if (catalogNumber in reminderFlags) {
        reminderFlags - catalogNumber
    } else {
        reminderFlags + catalogNumber
    }
    return copy(reminderFlags = updated)
}

/**
 * 把历史「收藏」数据迁移进内置分类，并同步写入提醒开关。
 *
 * 语义：升级前被收藏（因而会自动提醒）的卫星，升级后
 * 1）归属到内置「我的关注」分类；2）仍然保持提醒 —— **提醒行为不回退**。
 *
 * 幂等：若内置分类已存在则复用；若配置中已有该分类，不会重复创建。
 * 无历史收藏时**不做任何改动**（全新安装不会凭空多出一个空分类）。
 *
 * @param legacyFavorites 历史 `FavoriteSatellitesStore` 中的 NORAD 编号集合。
 * @return 迁移后的配置。`legacyFavorites` 为空时原样返回。
 */
fun SatelliteCategoryConfig.migrateLegacyFavorites(
    legacyFavorites: Set<Int>,
): SatelliteCategoryConfig {
    // NORAD 编号必须为正：与导入校验保持一致，避免产生「归属能存下、
    // 提醒开关却被过滤掉」的不一致数据
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

/** NORAD 编号是否合法（统一校验入口）。 */
fun isValidCatalogNumber(catalogNumber: Int): Boolean = catalogNumber > 0
