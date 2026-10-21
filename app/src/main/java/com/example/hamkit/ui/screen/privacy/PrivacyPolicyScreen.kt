package com.example.hamkit.ui.screen.privacy

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hamkit.R

/**
 * 离线隐私政策页。
 *
 * 不依赖网络、不启用 JavaScript；内容随 APK 一起发布，保证首启同意时也能阅读全文。
 */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val html = remember(context) { PrivacyPolicyAssets.load(context) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.privacy_policy_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                // 与左侧“返回”等宽，保证标题视觉居中
                Spacer(Modifier.width(64.dp))
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
    }
}

private object PrivacyPolicyAssets {

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
