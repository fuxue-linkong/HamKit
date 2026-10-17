package com.example.hamkit.ui.screen.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.R
import com.example.hamkit.ui.AMapCard
import com.example.hamkit.ui.AMapCardMaterial
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 定位详情子页面：定位状态 + 位置地图（同一页面）。
 *
 * 主页仅保留一个入口卡跳转到此页面，定位状态文本与高德地图在此合并展示。
 */
@Composable
fun LocationDetailScreen() {
    val navigator = LocalNavigator.current
    val mainViewModel = LocalMainViewModel.current
    val locationState by mainViewModel.locationState
    val onBack = dropUnlessResumed { navigator.pop() }

    when (LocalUiMode.current) {
        UiMode.Miuix -> LocationDetailScreenMiuix(
            locationState = locationState,
            onRefresh = { mainViewModel.refreshLocation() },
            onBack = onBack,
        )

        UiMode.Material -> LocationDetailScreenMaterial(
            locationState = locationState,
            onRefresh = { mainViewModel.refreshLocation() },
            onBack = onBack,
        )
    }
}

@Composable
private fun LocationDetailScreenMiuix(
    locationState: com.example.hamkit.ui.LocationUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    MiuixScaffold(
        topBar = {
            MiuixTopAppBar(
                title = stringResource(R.string.location_detail_title),
                navigationIcon = {
                    MiuixIconButton(onClick = onBack) {
                        MiuixIcon(MiuixIcons.Back, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null,
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item { LocationCardMiuix(locationState, onRefresh) }
            item {
                locationState.result?.let { loc ->
                    AMapCard(latitude = loc.latitude, longitude = loc.longitude)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun LocationCardMiuix(
    state: com.example.hamkit.ui.LocationUiState,
    onRefresh: () -> Unit,
) {
    val loc = state
    val result = loc.result
    MiuixCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiuixText(
                    text = stringResource(R.string.location_status),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                MiuixTextButton(
                    onClick = onRefresh,
                    text = if (loc.isLoading) stringResource(R.string.locating) else stringResource(R.string.action_refresh)
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                loc.isLoading && result == null -> MiuixText(
                    text = stringResource(R.string.locating),
                    color = colorScheme.onSurfaceVariantSummary
                )
                loc.error != null && result == null -> MiuixText(
                    text = stringResource(R.string.location_failed),
                    color = colorScheme.onError
                )
                result != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LocationRowMiuix(stringResource(R.string.latitude), "%.4f°".format(result.latitude))
                    LocationRowMiuix(stringResource(R.string.longitude), "%.4f°".format(result.longitude))
                    result.cqZone?.let { LocationRowMiuix(stringResource(R.string.cq_zone), it.toString()) }
                    result.ituZone?.let { LocationRowMiuix(stringResource(R.string.itu_zone), it.toString()) }
                    LocationRowMiuix(stringResource(R.string.maidenhead), result.maidenhead)
                    if (loc.lastLocationCity.isNotBlank()) {
                        LocationRowMiuix(stringResource(R.string.address), loc.lastLocationCity)
                    }
                }
                else -> MiuixText(
                    text = stringResource(R.string.tap_to_locate),
                    color = colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun LocationRowMiuix(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MiuixText(text = title, color = colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
        MiuixText(text = value, color = colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationDetailScreenMaterial(
    locationState: com.example.hamkit.ui.LocationUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.location_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(0.dp))
            // Material 分支全程用 material3 组件：卡片与地图外壳都走 Material 版实现，
            // 因此这里不再需要 MiuixTheme 包裹（原先是为了让 Miuix 的地图卡片正确取色）
            LocationCardMaterial(locationState, onRefresh)
            locationState.result?.let { loc ->
                AMapCardMaterial(latitude = loc.latitude, longitude = loc.longitude)
            }
        }
    }
}

@Composable
private fun LocationCardMaterial(
    state: com.example.hamkit.ui.LocationUiState,
    onRefresh: () -> Unit,
) {
    val loc = state
    val result = loc.result
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.location_status),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onRefresh) {
                    Text(if (loc.isLoading) stringResource(R.string.locating) else stringResource(R.string.action_refresh))
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                loc.isLoading && result == null -> Text(
                    text = stringResource(R.string.locating),
                    style = MaterialTheme.typography.bodyMedium
                )
                loc.error != null && result == null -> Text(
                    text = stringResource(R.string.location_failed),
                    color = MaterialTheme.colorScheme.error
                )
                result != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LocationRowMaterial(stringResource(R.string.latitude), "%.4f°".format(result.latitude))
                    LocationRowMaterial(stringResource(R.string.longitude), "%.4f°".format(result.longitude))
                    result.cqZone?.let { LocationRowMaterial(stringResource(R.string.cq_zone), it.toString()) }
                    result.ituZone?.let { LocationRowMaterial(stringResource(R.string.itu_zone), it.toString()) }
                    LocationRowMaterial(stringResource(R.string.maidenhead), result.maidenhead)
                    if (loc.lastLocationCity.isNotBlank()) {
                        LocationRowMaterial(stringResource(R.string.address), loc.lastLocationCity)
                    }
                }
                else -> Text(
                    text = stringResource(R.string.tap_to_locate),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun LocationRowMaterial(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
