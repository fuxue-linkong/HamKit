package com.example.hamkit.ui.screen.privacy

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode

/**
 * 离线隐私政策页（按 [UiMode] 分发）。
 *
 * 不依赖网络、不启用 JavaScript；内容随 APK 一起发布，保证首启同意时也能阅读全文。
 *
 * 两种风格共享 [PolicyWebView] 与 [PrivacyPolicyAssets]，只有顶栏不同：
 * Miuix 用 SmallTopAppBar，Material 用与既有页面一致的轻量返回行。
 */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    when (LocalUiMode.current) {
        UiMode.Miuix -> PrivacyPolicyScreenMiuix(onBack = onBack)
        UiMode.Material -> PrivacyPolicyScreenMaterial(onBack = onBack)
    }
}

/**
 * 政策全文的 WebView。
 *
 * 安全约束：禁用 JS、文件访问与 DOM 存储；站外链接（http/https/mailto）交给系统浏览器，
 * 避免 WebView 内弹出无法返回的外部页面。
 */
@Composable
internal fun PolicyWebView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val target = request.url.toString()
                        if (target.startsWith("http://") ||
                            target.startsWith("https://") ||
                            target.startsWith("mailto:")
                        ) {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                            }
                            return true
                        }
                        return false
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

/** 政策 HTML 资源读取（按语言回落到中文）。 */
internal object PrivacyPolicyAssets {

    @Composable
    fun rememberHtml(): String {
        val context = LocalContext.current
        return remember(context) { load(context) }
    }

    fun load(context: android.content.Context): String {
        val language = context.resources.configuration.locales[0].language
        val preferred = if (language.startsWith("zh")) {
            "privacy_policy_zh.html"
        } else {
            "privacy_policy_en.html"
        }
        return runCatching {
            context.assets.open(preferred).bufferedReader().use { it.readText() }
        }.getOrElse {
            context.assets.open("privacy_policy_zh.html").bufferedReader().use { it.readText() }
        }
    }
}
