package com.example.hamkit

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.work.Configuration
import com.example.hamkit.data.privacy.AmapPrivacyInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

/**
 * 全局 Application 实例。供 SettingsRepository 等不持有 Context 的代码访问应用上下文。
 */
lateinit var radioApp: HamKitApplication

/**
 * 应用入口。
 *
 * 启动关键路径（主线程同步执行）：
 * 1. 高德 SDK 初始化：**仅当历史隐私同意仍有效时**调用；首次启动会跳过，
 *    由 PrivacyConsentScreen 取得同意后再初始化
 *
 * 后台初始化（IO 线程异步执行，不阻塞首帧）：
 * 3. Android 14+ 启用 OnBackInvokedCallback（依赖 hiddenapibypass）
 * 4. OkHttpClient 单例（带 10MB 缓存 + User-Agent 拦截器）
 *
 * 拆分原则：只把不阻塞 UI 显示、且无严格时序依赖的初始化放到后台线程，
 * 避免与 Compose 首帧、高德 SDK native 库加载等抢占主线程资源。
 *
 * 未调用隐私合规接口会导致 errorCode 555570（隐私合规校验失败）及 native library 加载失败。
 */
class HamKitApplication : Application(), ViewModelStoreOwner, Configuration.Provider {

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    /**
     * OkHttpClient 单例。
     *
     * 使用 [lazy] 在首次访问时构建，避免在 [onCreate] 主线程上同步创建 10MB 缓存目录。
     * 首次访问通常发生在 IO 线程发起网络请求时，构建开销不会阻塞 UI。
     * 构建失败时回退到无缓存的最简客户端，保证网络功能可用。
     */
    val okhttpClient: OkHttpClient by lazy {
        try {
            OkHttpClient.Builder()
                .cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "HamKit/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag())
                            .build()
                    )
                }.build()
        } catch (_: Throwable) {
            OkHttpClient.Builder().build()
        }
    }

    /** 后台初始化用的协程作用域，[SupervisorJob] 保证单个子协程失败不影响其他。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appViewModelStore by lazy { ViewModelStore() }

    override fun onCreate() {
        super.onCreate()
        radioApp = this

        // ── 启动关键路径（主线程同步）──────────────────────────────────
        // 高德 SDK 隐私合规：只有已同意隐私政策的历史用户才在这里初始化。
        // 首次启动会在 PrivacyConsentScreen 中取得同意后再调用；未同意时这里直接返回，
        // 不会触发高德 SDK 的任何接口。
        AmapPrivacyInitializer.initialize(this)

        // ── 后台初始化（IO 线程异步，不阻塞首帧）──────────────────────
        // 4. Android 14+ 启用 OnBackInvokedCallback（依赖 hiddenapibypass）
        //    包含 SharedPreferences 读取 + hidden API 反射调用，约 10-30ms，
        //    且 OnBackInvokedCallback 仅在用户触发返回手势时才需要，
        //    放后台线程不会影响冷启动关键路径。
        //    即使 back gesture 在初始化完成前到达，最坏情况是降级到 legacy back behavior（不崩溃）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            appScope.launch {
                try {
                    val prefs = this@HamKitApplication
                        .getSharedPreferences("settings", MODE_PRIVATE)
                    val enable = prefs.getBoolean("enable_predictive_back", false)
                    HiddenApiBypass.addHiddenApiExemptions(
                        "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"
                    )
                    setEnableOnBackInvokedCallback(applicationInfo, enable)
                } catch (_: Throwable) {
                    // hidden API 绕过失败不影响应用启动
                }
            }
        }

        // 5. OkHttpClient 单例改为按需 lazy 构建（见 [okhttpClient]），
        //    避免在 onCreate 主线程上同步创建 10MB 缓存目录。
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore

    /**
     * WorkManager 按需初始化配置。
     *
     * AndroidManifest 中已禁用 WorkManagerInitializer（androidx.startup），
     * 改为由 Application 实现 [Configuration.Provider]。
     * 首次调用 [WorkManager.getInstance] 时，WorkManager 会读取此配置自动完成初始化，
     * 避免在 ContentProvider 阶段（冷启动关键路径）初始化 WorkManager。
     *
     * 这样 ReminderBootReceiver（开机/应用更新时触发）和 ReminderScheduler
     * 都能安全调用 WorkManager.getInstance()。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
