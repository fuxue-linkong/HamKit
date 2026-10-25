package com.example.hamkit.ui.screen.privacy

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.hamkit.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 隐私政策全文页（Miuix 风格）。
 *
 * 顶栏使用 [SmallTopAppBar] + 返回图标；正文复用 [PolicyWebView]，
 * 与 Material 版内容完全一致，仅外壳不同。
 */
@Composable
fun PrivacyPolicyScreenMiuix(onBack: () -> Unit) {
    val html = PrivacyPolicyAssets.rememberHtml()

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.privacy_policy_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        // 顶栏自行处理状态栏，因此这里只保留水平方向的安全区，避免顶部双重留白
        contentWindowInsets =
            WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        PolicyWebView(
            html = html,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 正文可滚动到底，需避开手势导航条，否则最后几行会被遮挡
                .navigationBarsPadding(),
        )
    }
}
