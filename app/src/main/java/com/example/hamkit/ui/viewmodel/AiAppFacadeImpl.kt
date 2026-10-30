package com.example.hamkit.ui.viewmodel

import com.example.hamkit.data.LocationResult
import com.example.hamkit.data.ai.tool.AiAppFacade
import com.example.hamkit.data.ai.tool.AiNavTarget
import com.example.hamkit.data.ai.tool.AiSatelliteRef
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.SatelliteCatalog
import com.example.hamkit.data.satellite.SatelliteInfo
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

    override fun resolveSatellite(query: String): AiSatelliteRef? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        trimmed.toIntOrNull()?.let { norad ->
            val passName = satellites().firstOrNull { it.catalogNumber == norad }?.name
            val tleName = mainViewModel.satelliteState.value.cachedTles
                .firstOrNull { it.tle.catnum == norad }?.tle?.name?.trim()
            val name = passName ?: tleName ?: mainViewModel.satelliteNameOf(norad)
            return name.takeIf { it.isNotBlank() }?.let { AiSatelliteRef(norad, it) }
        }

        val lower = trimmed.lowercase()
        satellites().firstOrNull { it.name.lowercase().contains(lower) }?.let {
            return AiSatelliteRef(it.catalogNumber, it.name)
        }
        mainViewModel.satelliteState.value.cachedTles
            .firstOrNull { it.tle.name.lowercase().contains(lower) }
            ?.let {
                return AiSatelliteRef(it.tle.catnum, it.tle.name.trim())
            }
        return null
    }

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
