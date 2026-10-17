package com.example.hamkit.ui

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.example.hamkit.R
import com.example.hamkit.data.location.CoordinateConverter
import com.example.hamkit.ui.theme.LocalCardAlpha

/**
 * 位置地图卡片的 Material（Material 3）风格实现。
 *
 * 高德地图的集成逻辑与 Miuix 版 [AMapCard] 逐行一致（MapView 生命周期、WGS84→GCJ02
 * 转换、标记与相机更新），差别只在卡片外壳与文字层级 —— 原先 Material 页面直接复用
 * [AMapCard]，会在一屏 Material 界面里出现一张 MIUI 配色的卡片。
 */
@Composable
fun AMapCardMaterial(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val markerTitle = stringResource(R.string.map_center_marker)

    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
        }
    }

    val aMap = remember(mapView) { mapView.map }

    // 高德地图 SDK 使用 GCJ02 坐标系，上游传入的是 WGS84，展示前转换（微秒级开销）
    val (gcjLat, gcjLng) = CoordinateConverter.wgs84ToGcj02(latitude, longitude)

    val lastCoord = remember { object { var lat = Double.NaN; var lng = Double.NaN } }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.map_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(16.dp))

            AndroidView(
                factory = {
                    aMap.apply {
                        uiSettings.isZoomControlsEnabled = true
                        uiSettings.isZoomGesturesEnabled = true
                        uiSettings.isScrollGesturesEnabled = true
                        uiSettings.isRotateGesturesEnabled = true
                        uiSettings.isTiltGesturesEnabled = true

                        val target = LatLng(gcjLat, gcjLng)
                        moveCamera(
                            CameraUpdateFactory.newLatLngZoom(target, DEFAULT_MAP_ZOOM)
                        )

                        clear()
                        addMarker(
                            MarkerOptions()
                                .position(target)
                                .title(markerTitle)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                        )
                        lastCoord.lat = gcjLat
                        lastCoord.lng = gcjLng
                    }
                    mapView
                },
                update = {
                    if (lastCoord.lat != gcjLat || lastCoord.lng != gcjLng) {
                        val target = LatLng(gcjLat, gcjLng)
                        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(target, DEFAULT_MAP_ZOOM))
                        aMap.clear()
                        aMap.addMarker(
                            MarkerOptions()
                                .position(target)
                                .title(markerTitle)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                        )
                        lastCoord.lat = gcjLat
                        lastCoord.lng = gcjLng
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = String.format(java.util.Locale.US, "%.4f, %.4f", latitude, longitude),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 与 [AMapCard] 保持同一缩放级别。 */
private const val DEFAULT_MAP_ZOOM = 15f
