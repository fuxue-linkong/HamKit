package com.example.hamkit.ui.screen.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.hamkit.BuildConfig
import com.example.hamkit.R
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.navigation3.Route

private const val PROJECT_REPO = "https://github.com/fuxue-linkong/Dual-zone_network_positioning"

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current

    val links = buildList {
        // 项目主页
        add(LinkInfo(stringResource(R.string.about_project_home), PROJECT_REPO))
        // 隐私政策：应用内离线页面，不跳浏览器
        add(LinkInfo(stringResource(R.string.about_privacy), "app://privacy"))
        // 问题反馈：直接跳转 Issues
        add(LinkInfo(stringResource(R.string.about_report_issue), "$PROJECT_REPO/issues"))
        // 开源许可
        add(LinkInfo(stringResource(R.string.about_license), "$PROJECT_REPO/blob/miuix/LICENSE"))
    }

    val state = AboutUiState(
        title = stringResource(R.string.about),
        appName = stringResource(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        description = stringResource(R.string.about_description),
        links = links,
        disclaimer = stringResource(R.string.about_disclaimer),
    )
    val actions = AboutScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenLink = { url ->
            if (url == "app://privacy") {
                navigator.push(Route.PrivacyPolicy)
            } else {
                uriHandler.openUri(url)
            }
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> AboutScreenMiuix(state, actions)
        UiMode.Material -> AboutScreenMaterial(state, actions)
    }
}
