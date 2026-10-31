package com.example.hamkit.ui.screen.aprs

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.applicationScopedViewModel
import com.example.hamkit.ui.viewmodel.AprsViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** APRS 符号（table + code + 名称），覆盖常见移动/固定/应急场景 */
data class AprsSymbol(val table: Char, val code: Char, val name: String)

/**
 * 可选符号表。
 *
 * 用 internal 而非 private：Material 版（[AprsSymbolPickerScreenMaterial]）要展示同一份列表，
 * 复制一份会导致两套实现的符号清单各自漂移，因此共用此单一数据源。
 */
internal val APRS_SYMBOLS = listOf(
    AprsSymbol('/', '>', "汽车"), AprsSymbol('/', '<', "摩托车"),
    AprsSymbol('/', 'k', "卡车"), AprsSymbol('/', 'u', "公交车"),
    AprsSymbol('/', 'b', "自行车"), AprsSymbol('/', 'j', "吉普车"),
    AprsSymbol('/', 'v', "房车"), AprsSymbol('/', 'U', "重型卡车"),
    AprsSymbol('/', 's', "小船"), AprsSymbol('/', 'y', "帆船"),
    AprsSymbol('/', 'C', "独木舟"), AprsSymbol('/', 'X', "直升机"),
    AprsSymbol('/', 'p', "飞机"), AprsSymbol('/', 'O', "热气球"),
    AprsSymbol('/', 'g', "滑翔机"), AprsSymbol('/', '[', "跑步者"),
    AprsSymbol('/', '/', "步行者"), AprsSymbol('/', 'H', "救护车"),
    AprsSymbol('/', 'F', "消防车"), AprsSymbol('/', 'P', "警车"),
    AprsSymbol('/', 'h', "房屋"), AprsSymbol('/', 'i', "小岛"),
    AprsSymbol('/', 'n', "节点"), AprsSymbol('/', 'r', "漫游车"),
    AprsSymbol('/', 't', "火车"), AprsSymbol('/', 'z', "避难所"),
    AprsSymbol('/', '#', "中继台"), AprsSymbol('/', '&', "HF 网关"),
    AprsSymbol('/', '$', "电话"), AprsSymbol('/', '!', "派出所"),
    AprsSymbol('/', 'a', "医疗机构"), AprsSymbol('/', 'm', "气象站"),
    AprsSymbol('/', 'w', "气象站"), AprsSymbol('/', 'R', "接收站"),
    AprsSymbol('/', 'T', "电话"), AprsSymbol('/', 'N', "导航"),
    AprsSymbol('\\', '>', "汽车(备用)"), AprsSymbol('\\', 's', "小船(备用)"),
    AprsSymbol('\\', 'h', "房屋(备用)"), AprsSymbol('\\', '#', "中继台(备用)"),
    AprsSymbol('\\', 'a', "应急(备用)"), AprsSymbol('\\', 'b', "自行车(备用)")
)

/**
 * APRS 符号选择页分发入口：按当前 UI 风格选择 Miuix / Material 实现。
 *
 * 两者共用同一个 application 级 [AprsViewModel] 实例，切换风格不会丢状态。
 */
@Composable
fun AprsSymbolPickerScreen(
    onNavigateBack: () -> Unit = {}
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> AprsSymbolPickerScreenMiuix(onNavigateBack = onNavigateBack)
        UiMode.Material -> AprsSymbolPickerScreenMaterial(onNavigateBack = onNavigateBack)
    }
}

@Composable
fun AprsSymbolPickerScreenMiuix(
    onNavigateBack: () -> Unit = {}
) {
    val viewModel = applicationScopedViewModel<AprsViewModel>()
    val config by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "选择 APRS 符号",
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
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = innerPadding.calculateTopPadding())) {
            // 当前符号预览
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text(
                        text = "当前: ",
                        style = MiuixTheme.textStyles.body1,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "${config.symbolTable}${config.symbolCode}",
                        style = MiuixTheme.textStyles.title1,
                        color = colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = APRS_SYMBOLS.firstOrNull {
                            it.table == config.symbolTable && it.code == config.symbolCode
                        }?.name ?: "自定义",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceSecondary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 符号网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(APRS_SYMBOLS) { symbol ->
                    val isSelected = symbol.table == config.symbolTable &&
                        symbol.code == config.symbolCode
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .then(
                                if (isSelected) Modifier.border(
                                    2.dp,
                                    colorScheme.primary
                                )
                                else Modifier
                            )
                            .clickable {
                                viewModel.updateSettings {
                                    it.copy(
                                        symbolTable = symbol.table,
                                        symbolCode = symbol.code
                                    )
                                }
                                onNavigateBack()
                            }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        ) {
                            Text(
                                text = "${symbol.table}${symbol.code}",
                                style = MiuixTheme.textStyles.title1,
                                color = colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = symbol.name,
                                style = MiuixTheme.textStyles.footnote1,
                                color = colorScheme.onSurfaceSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
