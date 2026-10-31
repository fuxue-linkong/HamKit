package com.example.hamkit.ui.viewmodel

import com.example.hamkit.data.LocationResult
import com.example.hamkit.data.ai.tool.AiAppFacade
import com.example.hamkit.data.ai.tool.AiNavTarget
import com.example.hamkit.data.ai.tool.AiSatelliteRef
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteCatalog
import com.example.hamkit.data.satellite.SatelliteInfo
import com.example.hamkit.data.satellite.SatelliteNameMatcher
import com.example.hamkit.data.satellite.SatelliteStatusQuery
import com.example.hamkit.data.satellite.SourcedTLE
import com.example.hamkit.data.weather.WeatherResult
import com.example.hamkit.ui.MainViewModel
import com.example.hamkit.ui.navigation3.Navigator
import com.example.hamkit.ui.navigation3.Route
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [AiAppFacade] 的 UI 层实现：把 MainViewModel 的 Compose State 与导航能力暴露给工具。
 *
 * 生命周期绑在 AI 页面上（页面 dispose 时 `detachFacade`），避免 application 级
 * ViewModel 长期持有 Navigator/Activity 相关对象。
 */
class AiAppFacadeImpl(
    private val mainViewModel: MainViewModel,
    private val navigator: Navigator,
) : AiAppFacade {

    override fun location(): LocationResult? = mainViewModel.locationState.value.result

    override suspend fun awaitLocation(timeoutMs: Long): LocationResult? {
        location()?.let { return it }
        withContext(Dispatchers.Main) { mainViewModel.refreshLocation() }
        return withTimeoutOrNull(timeoutMs) {
            snapshotFlow { mainViewModel.locationState.value.result }
                .filterNotNull()
                .first()
        }
    }

    override fun satellites(): List<SatelliteInfo> = mainViewModel.satelliteState.value.satellites

    override suspend fun ensureSatellites(timeoutMs: Long): List<SatelliteInfo> {
        satellites().takeIf { it.isNotEmpty() }?.let { return it }
        withContext(Dispatchers.Main) { mainViewModel.refreshSatelliteSourceOnly() }
        return withTimeoutOrNull(timeoutMs) {
            snapshotFlow { mainViewModel.satelliteState.value.satellites }
                .first { it.isNotEmpty() }
        } ?: emptyList()
    }

    override fun tleFor(catalogNumber: Int): SourcedTLE? =
        mainViewModel.satelliteState.value.cachedTles
            .firstOrNull { it.tle.catnum == catalogNumber }

    override fun radios(catalogNumber: Int): List<RadioInfo> =
        mainViewModel.radioMap.value[catalogNumber].orEmpty()

    override fun amsatStatusName(catalogNumber: Int): String? =
        SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER[catalogNumber]

    override fun amsatStatus(name: String): SatelliteStatusQuery? =
        mainViewModel.statusTracker.queryStatus(name)

    override fun weather(): WeatherResult? = mainViewModel.weather.value

    override fun resolveSatellite(query: String): AiSatelliteRef? =
        satelliteCandidates(query).singleOrNull()

    /**
     * 名称/编号 → 候选列表。
     *
     * 名称来源合并“预测结果名称”和“全量 TLE 名称”：前者与卫星列表展示一致，
     * 后者覆盖当前没有过境、因此不在预测结果里的卫星。
     *
     * 名称匹配走 [SatelliteNameMatcher] 的分层规则（精确 → 前缀 → 单词 → 子串），
     * 同一层多颗卫星时返回全部候选，由工具层提示改用 NORAD，避免 `ISS`
     * 因 `MISSION` 含 `iss` 而静默命中 `OUTPOST MISSION2`。
     */
    override fun satelliteCandidates(query: String): List<AiSatelliteRef> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val namesByCatalog = LinkedHashMap<Int, String>()
        satellites().forEach { namesByCatalog[it.catalogNumber] = it.name }
        mainViewModel.satelliteState.value.cachedTles.forEach { sourced ->
            val catnum = sourced.tle.catnum
            if (!namesByCatalog.containsKey(catnum)) {
                val name = sourced.tle.name.trim()
                if (name.isNotEmpty()) namesByCatalog[catnum] = name
            }
        }
        if (namesByCatalog.isEmpty()) return emptyList()

        trimmed.toIntOrNull()?.let { norad ->
            // 数字查询必须是已知 NORAD，避免对不存在的编号返回“名称=编号”的假引用
            val name = namesByCatalog[norad] ?: return emptyList()
            return listOf(AiSatelliteRef(norad, name))
        }

        val candidates = namesByCatalog.map { (catnum, name) ->
            SatelliteNameMatcher.Candidate(catnum, name)
        }
        return when (val resolution = SatelliteNameMatcher.resolve(trimmed, candidates)) {
            is SatelliteNameMatcher.Resolution.Unique ->
                listOf(resolution.candidate.toRef())
            is SatelliteNameMatcher.Resolution.Ambiguous ->
                resolution.candidates.map { it.toRef() }
            SatelliteNameMatcher.Resolution.NotFound -> emptyList()
        }
    }

    private fun SatelliteNameMatcher.Candidate.toRef(): AiSatelliteRef =
        AiSatelliteRef(catalogNumber = catalogNumber, name = name)

    override fun navigate(target: AiNavTarget) {
        navigator.push(target.toRoute())
    }

    override fun startSstvReceive(presetMode: String?) {
        navigator.push(Route.SstvMain(presetModeName = presetMode, autoStart = true))
    }

    override fun setReminder(catalogNumber: Int, enabled: Boolean) {
        mainViewModel.setSatelliteReminderEnabled(catalogNumber, enabled)
    }

    private fun AiNavTarget.toRoute(): Route = when (this) {
        AiNavTarget.SSTV_RECEIVE -> Route.SstvMain()
        AiNavTarget.SSTV_GALLERY -> Route.SstvGallery()
        AiNavTarget.SSTV_TX -> Route.SstvTx
        AiNavTarget.CW -> Route.CWPractice
        AiNavTarget.FT8 -> Route.Ft8Main
        AiNavTarget.APRS -> Route.AprsMain
        AiNavTarget.QSO_LOG -> Route.QsoLogList
        AiNavTarget.SATELLITE_LIST -> Route.SatelliteManagement
        AiNavTarget.REMINDER_LIST -> Route.ReminderList
        AiNavTarget.AI_SETTINGS -> Route.AiSettings
    }
}
