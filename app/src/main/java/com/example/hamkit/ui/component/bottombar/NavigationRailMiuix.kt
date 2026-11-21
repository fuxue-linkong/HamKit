package com.example.hamkit.ui.component.bottombar

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.hamkit.ui.LocalMainPagerState
import com.example.hamkit.ui.util.BlurredBar
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.example.hamkit.ui.theme.Radii

@Composable
fun NavigationRailMiuix(
    blurBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val mainState = LocalMainPagerState.current

    val items = BottomBarDestination.entries.map { destination ->
        Pair(stringResource(destination.label), destination.icon)
    }

    // 平板/大屏：Rail 展开，图标右侧带文字标签（折叠态保持纯图标居中）
    if (expanded) {
        BlurredBar(blurBackdrop) {
            Column(
                modifier = modifier
                    .fillMaxHeight()
                    .background(
                        if (blurBackdrop != null) Color.Transparent
                        else MiuixTheme.colorScheme.surface,
                    )
                    .padding(horizontal = 8.dp).padding(vertical = 16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                items.forEachIndexed { index, (label, icon) ->
                    val selected = mainState.selectedPage == index
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.Control))
                            .background(
                                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else Color.Transparent,
                            )
                            .clickable { mainState.animateToPage(index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = if (selected) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onBackground
                            },
                        )
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (selected) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onBackground
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        return
    }

    BlurredBar(blurBackdrop) {
        NavigationRail(
            modifier = modifier
                .fillMaxHeight(),
            color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            items.forEachIndexed { index, (label, icon) ->
                NavigationRailItem(
                    icon = icon,
                    label = label,
                    selected = mainState.selectedPage == index,
                    onClick = {
                        mainState.animateToPage(index)
                    },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
