package com.example.hamkit.data.satellite

/**
 * 卫星名称/编号匹配器：把用户或模型给出的查询串与卫星列表做分层匹配。
 *
 * 为什么不能直接 `name.contains(query)`：`ISS` 是 `MISSION` 的子串，
 * 早期实现因此把 `ISS` 错误解析成 `OUTPOST MISSION2`。这里按精确度分层，
 * 只有更高层没有候选时才退到子串层。
 *
 * 分层顺序（数值越小越精确）：
 * 0. NORAD 编号全等
 * 1. 归一化完整名称全等，或“括号前主名称”全等（如 `ISS (ZARYA)` 的主名称是 `ISS`）
 * 2. 主名称/完整名称前缀
 * 3. 单词边界匹配（`ISS` 不命中 `MISSION` 中间的子串）
 * 4. 子串
 *
 * 同一层命中多颗卫星时返回 [Resolution.Ambiguous]，由调用方提示改用 NORAD 编号，
 * 禁止静默取第一条。
 *
 * 位置：放在共享内核（commonMain）而不是平台侧，Android 的 AI 工具与 iOS 的
 * AI 工具因此共用同一套匹配规则，不会两边对同一句 "ISS" 给出不同答案。
 */
object SatelliteNameMatcher {

    /** 匹配候选：NORAD 编号 + 展示名称。 */
    data class Candidate(
        val catalogNumber: Int,
        val name: String,
    )

    /** 解析结果：唯一 / 有歧义 / 未找到。 */
    sealed interface Resolution {
        data class Unique(val candidate: Candidate) : Resolution
        data class Ambiguous(val candidates: List<Candidate>) : Resolution
        data object NotFound : Resolution
    }

    /** NORAD 编号全等层。 */
    const val TIER_NORAD = 0

    /** 完整名称或主名称全等层。 */
    const val TIER_NAME_EXACT = 1

    /** 名称前缀层。 */
    const val TIER_NAME_PREFIX = 2

    /** 单词边界层。 */
    const val TIER_WORD = 3

    /** 子串层（最低优先级）。 */
    const val TIER_SUBSTRING = 4

    /**
     * 计算 [query] 与 [candidate] 的匹配层级；不匹配返回 null。
     *
     * 纯数字查询只按 NORAD 编号全等匹配，避免把编号当成名称子串。
     */
    fun matchTier(query: String, candidate: Candidate): Int? {
        val normalized = normalizeQuery(query)
        if (normalized.isEmpty()) return null
        val norad = normalized.toIntOrNull()
        if (norad != null) {
            return if (candidate.catalogNumber == norad) TIER_NORAD else null
        }
        return nameTier(normalized, candidate.name)
    }

    /**
     * 仅按名称计算匹配层级（供搜索复用）；不匹配返回 null。
     *
     * `query` 必须已归一化，或由本函数内部归一化。
     */
    fun nameTier(query: String, name: String): Int? {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return null
        // 数字查询不参与名称匹配（NORAD 由 [matchTier] 处理）
        if (q.toIntOrNull() != null) return null

        val fullName = normalizeName(name)
        if (fullName.isEmpty()) return null
        val mainName = normalizeName(mainNameOf(name))

        return when {
            fullName == q || mainName == q -> TIER_NAME_EXACT
            mainName.startsWith(q) || fullName.startsWith(q) -> TIER_NAME_PREFIX
            containsAsWord(fullName, q) -> TIER_WORD
            fullName.contains(q) -> TIER_SUBSTRING
            else -> null
        }
    }

    /**
     * 按匹配层级、名称长度、NORAD 编号排序候选；不匹配的候选被丢弃。
     *
     * 名称越短通常越贴近查询（如 `ISS (ZARYA)` 优先于 `ISS (NAUKA) ...` 这类派生名）。
     */
    fun rank(query: String, candidates: List<Candidate>): List<Candidate> =
        candidates
            .mapNotNull { candidate ->
                matchTier(query, candidate)?.let { tier -> tier to candidate }
            }
            .sortedWith(
                compareBy({ it.first }, { it.second.name.length }, { it.second.catalogNumber })
            )
            .map { it.second }

    /**
     * 解析查询：取命中的最高层候选。
     *
     * - 该层只有一颗 → [Resolution.Unique]
     * - 该层多颗 → [Resolution.Ambiguous]（调用方应提示用 NORAD 指定）
     * - 无命中 → [Resolution.NotFound]
     */
    fun resolve(query: String, candidates: List<Candidate>): Resolution {
        val normalized = normalizeQuery(query)
        if (normalized.isEmpty()) return Resolution.NotFound

        val tiered = candidates.mapNotNull { candidate ->
            matchTier(normalized, candidate)?.let { tier -> tier to candidate }
        }
        if (tiered.isEmpty()) return Resolution.NotFound

        val bestTier = tiered.minOf { it.first }
        val best = tiered
            .filter { it.first == bestTier }
            .map { it.second }
            .sortedWith(compareBy({ it.name.length }, { it.catalogNumber }))

        return if (best.size == 1) {
            Resolution.Unique(best.single())
        } else {
            Resolution.Ambiguous(best)
        }
    }

    /** 取名称中第一个 `(` 之前的部分作为主名称（如 `ISS (ZARYA)` → `ISS`）。 */
    private fun mainNameOf(name: String): String =
        name.substringBefore('(').ifBlank { name }

    /** 查询串归一化：去首尾空白、转小写、压缩连续空白。 */
    private fun normalizeQuery(raw: String): String =
        raw.trim().lowercase().replace(WHITESPACE, " ")

    /** 名称归一化：与查询串使用同一规则，避免大小写/多空格导致漏配。 */
    private fun normalizeName(raw: String): String =
        raw.trim().lowercase().replace(WHITESPACE, " ")

    /**
     * 是否以独立单词出现：前后不能紧邻字母/数字。
     *
     * 这样 `ISS` 命中 `ISS (ZARYA)`、`Fox-1B` 命中 `AO-91 (Fox-1B)`，
     * 但不会命中 `MISSION` 内部的 `iss`。
     */
    private fun containsAsWord(haystack: String, needle: String): Boolean {
        val pattern = Regex("(^|[^a-z0-9])" + Regex.escape(needle) + "([^a-z0-9]|$)")
        return pattern.containsMatchIn(haystack)
    }

    private val WHITESPACE = Regex("\\s+")
}
