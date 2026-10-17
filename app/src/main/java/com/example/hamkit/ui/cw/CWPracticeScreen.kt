package com.example.hamkit.ui.cw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import com.example.hamkit.ui.theme.LocalCardAlpha
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix（MIUI）风格实现：CW 练习主菜单。 */
@Composable
fun CWPracticeScreenMiuix(
    onBackClick: () -> Unit,
    onFreePracticeClick: () -> Unit,
    onTutorialClick: () -> Unit,
    onMorseCodeClick: () -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CWPracticeListItem(
                title = stringResource(R.string.free_practice),
                description = stringResource(R.string.free_practice_desc),
                onClick = onFreePracticeClick
            )
        }
        item {
            CWPracticeListItem(
                title = stringResource(R.string.tutorial_practice),
                description = stringResource(R.string.tutorial_practice_desc),
                onClick = onTutorialClick
            )
        }
        item {
            CWPracticeListItem(
                title = stringResource(R.string.morsecode_codec),
                description = stringResource(R.string.morsecode_codec_desc),
                onClick = onMorseCodeClick
            )
        }
    }
}

@Composable
private fun CWPracticeListItem(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surface.copy(alpha = LocalCardAlpha.current),
            contentColor = MiuixTheme.colorScheme.onSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
