package com.example.hamkit.ui.navigation3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * 双栏场景的最小窗口宽度（dp）。
 *
 * 与导航断点（600dp）分开：600dp 只够放侧栏 + 单栏内容，
 * 真要并排「列表 + 详情」需要更宽，840dp 是 Material 大屏规范的 Expanded 起点。
 */
private const val TWO_PANE_MIN_WINDOW_DP = 840

/**
 * 双栏中的角色：左列表、右详情。
 *
 * 用角色（而不是只用一个布尔标记）配对，避免「详情 → 详情」也被误判成双栏。
 */
enum class TwoPaneRole { List, Detail }

/** 路由元数据键：记录该路由在双栏中的角色 */
internal object TwoPaneKey : NavMetadataKey<TwoPaneRole>

/**
 * 列表-详情双栏场景：把返回栈顶部的两个 entry 并排渲染。
 *
 * 仅在 [enabled] 为真（窗口够宽）且最后两个 entry 恰为
 * 「列表（[TwoPaneRole.List]）+ 详情（[TwoPaneRole.Detail]）」时启用；
 * 否则返回 null，由 NavDisplay 回落到常规单页场景，窄屏行为完全不变。
 */
class TwoPaneSceneStrategy<T : Any>(
    private val enabled: Boolean,
) : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!enabled) return null

        // 只有「列表 → 详情」两层同时存在时才并排；单层时保持全屏
        val lastTwo = entries.takeLast(2)
        if (lastTwo.size != 2) return null

        val firstRole = lastTwo[0].metadata[TwoPaneKey] as? TwoPaneRole ?: return null
        val secondRole = lastTwo[1].metadata[TwoPaneKey] as? TwoPaneRole ?: return null
        if (firstRole != TwoPaneRole.List || secondRole != TwoPaneRole.Detail) return null

        val firstEntry = lastTwo.first()
        val secondEntry = lastTwo.last()

        return TwoPaneScene(
            // 场景 key 必须唯一标识当前双栏状态，避免两个不同详情复用同一场景
            key = firstEntry.contentKey to secondEntry.contentKey,
            // 返回语义：宽屏并排时按一次返回只弹一层（关闭详情、回到列表）
            previousEntries = entries.dropLast(1),
            firstEntry = firstEntry,
            secondEntry = secondEntry,
        )
    }
}

/**
 * 双栏 [Scene]：左列表、右详情，按权重分配宽度（列表略窄）。
 */
class TwoPaneScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    val firstEntry: NavEntry<T>,
    val secondEntry: NavEntry<T>,
) : Scene<T> {

    override val entries: List<NavEntry<T>> = listOf(firstEntry, secondEntry)

    override val content: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
            ) {
                firstEntry.Content()
            }
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
            ) {
                secondEntry.Content()
            }
        }
    }

    companion object {
        /** 标记该路由在双栏中的角色（列表 / 详情） */
        fun twoPane(role: TwoPaneRole): Map<String, Any> = metadata { put(TwoPaneKey, role) }
    }
}

/**
 * 按当前窗口宽度创建双栏策略；窗口宽度变化（旋转 / 分屏 / 折叠）会重建。
 */
@Composable
fun rememberTwoPaneSceneStrategy(): TwoPaneSceneStrategy<NavKey> {
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    return remember(windowWidthDp) {
        TwoPaneSceneStrategy(enabled = windowWidthDp >= TWO_PANE_MIN_WINDOW_DP)
    }
}
