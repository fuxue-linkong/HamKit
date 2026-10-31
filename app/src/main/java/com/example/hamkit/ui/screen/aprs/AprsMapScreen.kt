package com.example.hamkit.ui.screen.aprs

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.example.hamkit.data.aprs.AprsStation
import com.example.hamkit.data.location.CoordinateConverter
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.viewmodel.AprsViewModel
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.util.Locale

/**
 * APRS 站点地图页分发入口：按当前 UI 风格选择 Miuix / Material 实现。
 *
 * 两者共用同一个 application 级 [AprsViewModel] 实例，切换风格不会丢站点数据。
 */
@Composable
fun AprsMapScreen(
    onNavigateBack: () -> Unit = {}
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> AprsMapScreenMiuix(onNavigateBack = onNavigateBack)

        UiMode.Material -> AprsMapScreenMaterial(onNavigateBack = onNavigateBack)
    }
}

@Composable
fun AprsMapScreenMiuix(
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<AprsViewModel>()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val config by viewModel.settings.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    // 呼号 → Marker 缓存：跨重组保留，已有 marker 只更新坐标，不重复添加（P2-7）
    val markersByCallsign = remember { mutableMapOf<String, Marker>() }
    val hasInitialized = remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "APRS 站点地图",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = colorScheme.onBackground
                        )
                    }
                }
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "共 ${stations.distinctBy { it.callsign.substringBefore("-") }.size} 个站点（点击标记查看详情）",
                style = MiuixTheme.textStyles.body2,
                color = colorScheme.onSurfaceSecondary,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(top = innerPadding.calculateTopPadding())
            )

            AndroidView(
                factory = {
                    mapView.onCreate(Bundle())
                    val aMap = mapView.map
                    aMap.uiSettings.isZoomControlsEnabled = true
                    mapView
                },
                update = { mv ->
                    val aMap = mv.map
                    val myCallsign = config.fullCallsign
                    val myStation = stations.find { it.callsign == myCallsign }
                    // 增量维护 marker：已存在的只 setPosition 跟随新坐标，不重复添加也不 clear（P2-7）
                    fun addMarkerFor(station: AprsStation, isMyStation: Boolean) {
                        // APRS 站点坐标为 WGS84，高德地图需 GCJ02
                        val (mLat, mLng) = CoordinateConverter.wgs84ToGcj02(station.latitude, station.longitude)
                        val existing = markersByCallsign[station.callsign]
                        if (existing != null) {
                            // 坐标有变化才更新，避免每次重组都重绘
                            if (existing.position.latitude != mLat || existing.position.longitude != mLng) {
                                existing.position = LatLng(mLat, mLng)
                            }
                            return
                        }
                        val marker = aMap.addMarker(
                            MarkerOptions()
                                .position(LatLng(mLat, mLng))
                                .title(if (isMyStation) "${station.callsign}（本站）" else station.callsign)
                                .snippet(
                                    buildString {
                                        append(
                                            String.format(
                                                Locale.US,
                                                "%.4f, %.4f",
                                                station.latitude,
                                                station.longitude
                                            )
                                        )
                                        if (station.comment.isNotEmpty()) {
                                            append("\n${station.comment}")
                                        }
                                    }
                                )
                        )
                        markersByCallsign[station.callsign] = marker
                    }
                    myStation?.let { addMarkerFor(it, true) }
                    stations.filter { it.callsign != myCallsign }.forEach { addMarkerFor(it, false) }
                    // 仅首次设置地图中心，避免每次 stations 更新都强制移回本站
                    if (!hasInitialized.value && stations.isNotEmpty()) {
                        val center = myStation ?: stations.first()
                        val (cLat, cLng) = CoordinateConverter.wgs84ToGcj02(center.latitude, center.longitude)
                        aMap.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(cLat, cLng), 12f
                            )
                        )
                        hasInitialized.value = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.dp)
                    .weight(1f)
            )
        }
    }
}
