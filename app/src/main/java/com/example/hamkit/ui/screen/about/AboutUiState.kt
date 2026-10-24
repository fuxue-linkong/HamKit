package com.example.hamkit.ui.screen.about

import androidx.compose.runtime.Immutable

@Immutable
data class ContributorInfo(
    val name: String,
    /** 业余无线电呼号，可为空 */
    val callsign: String? = null,
    /** GitHub 用户名，用于拼接头像与主页链接 */
    val githubId: String,
)

@Immutable
data class AboutUiState(
    val title: String,
    val appName: String,
    val versionName: String,
    val description: String,
    val links: List<LinkInfo>,
    val developers: List<ContributorInfo>,
    val donors: List<ContributorInfo>,
    val disclaimer: String,
)

@Immutable
data class AboutScreenActions(
    val onBack: () -> Unit,
    val onOpenLink: (String) -> Unit,
)
