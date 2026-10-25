package com.example.hamkit.data.repository

interface SettingsRepository {
    var uiMode: String
    var checkUpdate: Boolean
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enablePredictiveBack: Boolean
    var enableBlur: Boolean
    var enableFloatingBottomBar: Boolean
    var enableFloatingBottomBarBlur: Boolean
    /** 大屏导航形态（见 [com.example.hamkit.ui.WideNavStyle]） */
    var wideNavStyle: String
    var pageScale: Float
    var customBackgroundUri: String
}
