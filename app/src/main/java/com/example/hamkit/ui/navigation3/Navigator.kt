package com.example.hamkit.ui.navigation3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Simple navigation helper that owns a back stack and result channels.
 * Supports push/replace/pop/popUntil and result APIs: navigateForResult/setResult/observeResult/clearResult.
 */
class Navigator(
    initialKey: NavKey
) {
    val backStack: SnapshotStateList<NavKey> = mutableStateListOf(initialKey)

    private val resultBus = mutableMapOf<String, MutableSharedFlow<Any>>()

    /**
     * Push a key onto the back stack.
     */
    fun push(key: NavKey) {
        backStack.add(key)
    }

    /**
     * Replace the top key, or push if the stack is empty.
     */
    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = key
        } else {
            backStack.add(key)
        }
    }

    /**
     * Replace the backstack with a new list of keys if the stack is not empty.
     */
    fun replaceAll(keys: List<NavKey>) {
        if (keys.isEmpty()) {
            return
        }
        if (backStack.isNotEmpty()) {
            backStack.clear()
            backStack.addAll(keys)
        }
    }


    /**
     * Pop the top key if present.
     */
    fun pop() {
        backStack.removeLastOrNull()
    }

    /**
     * Pop until predicate matches the top key.
     */
    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.isNotEmpty() && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    /**
     * 确保返回栈至少包含指定的最短路径（按顺序、含栈底）。
     *
     * 用途：外部 Activity（系统图库/裁剪页）在前台期间，返回事件可能被派发回
     * 本应用的 Activity 级 BackHandler 导致栈被意外 pop。选图成功后调用本方法
     * 把栈收敛为最短路径，保证之后无论怎么 pop 都不会多退一层或直接退出。
     *
     * 实现：先按 [keys] 顺序重建栈底，再保留调用方当前栈顶（若不在 keys 中）。
     */
    fun ensureMinimumBackStack(vararg keys: NavKey) {
        if (keys.isEmpty()) return
        val currentTop = backStack.lastOrNull()
        backStack.clear()
        keys.forEach { backStack.add(it) }
        // 当前栈顶若不在最短路径里（如中间还有别的子页），保留在最顶上，避免丢页面
        if (currentTop != null && backStack.none { it == currentTop }) {
            backStack.add(currentTop)
        }
    }

    /**
     * Navigate expecting a result. Caller should subscribe via observeResult(requestKey).
     */
    fun navigateForResult(route: Route, requestKey: String) {
        ensureChannel(requestKey)
        push(route)
    }

    /**
     * Set a result for the given request and then pop.
     */
    fun <T : Any> setResult(requestKey: String, value: T) {
        ensureChannel(requestKey).tryEmit(value)
        pop()
    }

    /**
     * Observe results for a given request key as a SharedFlow.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observeResult(requestKey: String): SharedFlow<T> {
        return ensureChannel(requestKey) as SharedFlow<T>
    }

    /**
     * Clear the last emitted result for the request key.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearResult(requestKey: String) {
        ensureChannel(requestKey).resetReplayCache()
    }

    /**
     * Get current NavKey on the back stack.
     */
    fun current(): NavKey? {
        return backStack.lastOrNull()
    }

    /**
     * Get current size of back stack.
     */
    fun backStackSize(): Int {
        return backStack.size
    }

    private fun ensureChannel(key: String): MutableSharedFlow<Any> {
        return resultBus.getOrPut(key) { MutableSharedFlow(replay = 1, extraBufferCapacity = 0) }
    }

    companion object {
        val Saver: Saver<Navigator, Any> = listSaver(save = { navigator ->
            navigator.backStack.toList()
        }, restore = { savedList ->
            // P3-9：兜底回退到启动路由 Route.Main（Route.Home 未在 entry 中注册，
            // 空栈恢复到它会导致白屏/崩溃）
            val initialKey = savedList.firstOrNull() ?: Route.Main
            val navigator = Navigator(initialKey)
            navigator.backStack.clear()
            navigator.backStack.addAll(savedList)
            navigator
        })
    }
}


@Composable
fun rememberNavigator(startRoute: NavKey): Navigator {
    return rememberSaveable(startRoute, saver = Navigator.Saver) {
        Navigator(startRoute)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}