package com.example.hamkit.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.HitokotoApiService
import com.example.hamkit.data.LandscapeImageStore
import com.example.hamkit.data.LocationResult
import com.example.hamkit.data.SettingsStore
import com.example.hamkit.data.location.LocationHelper
import com.example.hamkit.data.reminder.ReminderItem
import com.example.hamkit.data.reminder.ReminderNotificationHelper
import com.example.hamkit.data.reminder.ReminderScheduler
import com.example.hamkit.data.reminder.ReminderSettings
import com.example.hamkit.data.reminder.ReminderStore
import com.example.hamkit.data.reminder.RepeatMode
import com.example.hamkit.data.satellite.AmsatStatusApiService
import com.example.hamkit.data.satellite.AmsatPageScraper
import com.example.hamkit.data.satellite.FavoriteSatellitesStore
import com.example.hamkit.data.satellite.RadioInfo
import com.example.hamkit.data.satellite.RadioInfoRepository
import com.example.hamkit.data.satellite.SatelliteCacheStore
import com.example.hamkit.data.satellite.SatelliteCatalog
import com.example.hamkit.data.satellite.SatelliteDataSource
import com.example.hamkit.data.satellite.SatelliteInfo
import com.example.hamkit.data.satellite.SatelliteListItem
import com.example.hamkit.data.satellite.SatellitePredictCacheStore
import com.example.hamkit.data.satellite.SatellitePredictor
import com.example.hamkit.data.satellite.SatelliteStatusTracker
import com.example.hamkit.data.satellite.SegmentStatus
import com.example.hamkit.data.satellite.SatelliteStatusSegmenter
import com.example.hamkit.data.satellite.SourcedTLE
import com.example.hamkit.data.weather.ApiKeyMissingException
import com.example.hamkit.data.weather.WeatherApiException
import com.example.hamkit.data.weather.WeatherApiService
import com.example.hamkit.data.weather.WeatherNetworkException
import com.example.hamkit.data.weather.WeatherResult
import com.example.hamkit.data.weather.WeatherStore
import com.example.hamkit.data.cw.CWProgress
import com.example.hamkit.data.cw.CWProgressStore
import com.example.hamkit.data.cw.CWSettings
import com.example.hamkit.data.cw.CWSettingsStore
import com.example.hamkit.data.cw.CharacterSet
import com.example.hamkit.data.cw.MorseCodeGenerator
import com.example.hamkit.data.cw.MorseCodePlayer
import com.example.hamkit.data.zone.ZoneResolver
import com.example.hamkit.radioApp
import com.example.hamkit.ui.util.ImageColorExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.io.File

/**
 * 主 ViewModel：定位、卫星过境预测、天气、每日一言、CW 练习、日程提醒。
 *
 * 使用普通 [ViewModel] + 全局 [radioApp] 上下文（与项目内 [com.example.hamkit.ui.viewmodel.HomeViewModel] 风格一致），
 * 不继承 [androidx.lifecycle.AndroidViewModel]，避免在 Navigation3 NavDisplay 内调用
 * `viewModel<MainViewModel>()` 时因 CreationExtras 缺少 `APPLICATION_KEY` 而崩溃。
 */
class MainViewModel : ViewModel() {

    private val app = radioApp

    private val locationHelper = LocationHelper(app)
    private val satelliteDataSource = SatelliteDataSource()
    private val satellitePredictor = SatellitePredictor()
    // SatNOGS 转发器频率库：卫星列表展示多收发器（频率/模式/状态）
    private val radioInfoRepository = RadioInfoRepository(app)
    // AMSAT 状态独立抓取服务：供状态跟踪器 5 分钟定时拉取，不依赖 TLE 拉取周期
    private val amsatStatusApi = AmsatStatusApiService()
    // AMSAT 页面抓取器：作为 API 的备选数据源
    private val amsatPageScraper = AmsatPageScraper()
    // 卫星状态持续显示跟踪器：96 个 15 分钟时间槽 + 状态延续算法
    val statusTracker = SatelliteStatusTracker(amsatStatusApi, amsatPageScraper, viewModelScope)
    private val settingsStore = SettingsStore(app)
    private val satelliteCache = SatelliteCacheStore(app)
    private val predictCache = SatellitePredictCacheStore(app)
    private val favoriteStore = FavoriteSatellitesStore(app)
    private val reminderStore = ReminderStore(app)
    private val weatherApiService = WeatherApiService()
    private val weatherStore = WeatherStore(app)
    private val reminderScheduler = ReminderScheduler(app)
    // 每日一言服务：从 https://v1.hitokoto.cn/ 获取，失败回退本地文案池
    private val hitokotoApi = HitokotoApiService()
    private val landscapeImageStore = LandscapeImageStore(app)

    // ---- CW练习模块 ----
    private val cwSettingsStore = CWSettingsStore(app)
    private val cwProgressStore = CWProgressStore(app)
    private val cwGenerator = MorseCodeGenerator()
    private val cwPlayer = MorseCodePlayer()

    // 跟踪上一次刷新的 Job，避免用户快速多次点击导致并发竞态
    private var refreshJob: Job? = null
    private var locationOnlyJob: Job? = null
    private var satelliteOnlyJob: Job? = null
    private var predictJob: Job? = null
    // 卫星分段状态拉取 Job：与主刷新解耦，结果异步回填 satelliteState.segmentStatuses
    private var segmentStatusJob: Job? = null
    // 分段状态最近一次拉取时间，1 小时内不重复请求 AMSAT
    private var segmentStatusFetchedAt: Instant? = null
    // 持续位置监听 Job：在首次成功定位后启动，自动跟踪设备位置变化
    private var locationUpdatesJob: Job? = null

    // 卫星过境预测结果缓存：避免同一坐标在短时间内重复执行 CPU 密集的 SGP4 计算。
    // 缓存有效期 15 分钟（PREDICTION_CACHE_TTL），坐标偏移超过 0.001° 时视为新位置需重新预测。
    private var cachedPredictionSatellites: List<SatelliteInfo>? = null
    private var cachedPredictionLat: Double = Double.NaN
    private var cachedPredictionLon: Double = Double.NaN
    private var cachedPredictionTime: Instant? = null
    // 地址解析去抖 Job：位置频繁变化时延后解析地址，避免 Geocoder 被密集调用
    private var addressDebounceJob: Job? = null
    private var weatherAutoRefreshJob: Job? = null
    private var initialized = false

    private val _locationState = mutableStateOf(LocationUiState())
    val locationState: State<LocationUiState> = _locationState

    private val _satelliteState = mutableStateOf(SatelliteUiState())
    val satelliteState: State<SatelliteUiState> = _satelliteState

    /** 转发器频率库（NORAD → 转发器列表，含 active/inactive） */
    private val _radioMap = mutableStateOf<Map<Int, List<RadioInfo>>>(emptyMap())
    val radioMap: State<Map<Int, List<RadioInfo>>> = _radioMap

    /**
     * 卫星列表项（TLE 全量 + 过境预测 + 转发器）：
     * 以缓存 TLE 为底（"卫星太少"：不再只显示有过境的卫星），
     * 预测结果按 NORAD 匹配附加，转发器按 NORAD 匹配。
     * 由 [satelliteState] 与 [radioMap] 派生，供卫星管理页使用。
     */
    val satelliteItems: List<SatelliteListItem>
        get() {
            val passes = _satelliteState.value.satellites.associateBy { it.catalogNumber }
            val radios = _radioMap.value
            return _satelliteState.value.cachedTles.map { sourcedTle ->
                SatelliteListItem(
                    tle = sourcedTle,
                    pass = passes[sourcedTle.tle.catnum],
                    radios = radios[sourcedTle.tle.catnum].orEmpty()
                )
            }
        }

    // CW练习状态
    private val _cwSettings = mutableStateOf(CWSettings())
    val cwSettings: State<CWSettings> = _cwSettings

    private val _cwCurrentText = mutableStateOf("")
    val cwCurrentText: State<String> = _cwCurrentText

    private val _cwMorseCode = mutableStateOf("")
    val cwMorseCode: State<String> = _cwMorseCode

    private val _cwUserInput = mutableStateOf("")
    val cwUserInput: State<String> = _cwUserInput

    private val _cwIsPlaying = mutableStateOf(false)
    val cwIsPlaying: State<Boolean> = _cwIsPlaying

    private val _cwIsPaused = mutableStateOf(false)
    val cwIsPaused: State<Boolean> = _cwIsPaused

    private val _cwAccuracy = mutableStateOf(0f)
    val cwAccuracy: State<Float> = _cwAccuracy

    /**
     * 卫星筛选状态。类型筛选为空集合时表示不按模式筛选。
     */
    private val _satelliteFilter = mutableStateOf(SatelliteFilter())
    val satelliteFilter: State<SatelliteFilter> = _satelliteFilter

    fun updateSatelliteFilter(filter: SatelliteFilter) {
        _satelliteFilter.value = filter
    }

    /**
     * 用户关注的卫星 NORAD 编号集合，进程重启后可从本地恢复。
     */
    private val _favoriteSatellites = mutableStateOf(favoriteStore.load())
    val favoriteSatellites: State<Set<Int>> = _favoriteSatellites

    /**
     * 日程提醒设置。从本地恢复，进程重启后保留用户偏好。
     */
    private val _reminderSettings = mutableStateOf(reminderStore.loadSettings())
    val reminderSettings: State<ReminderSettings> = _reminderSettings

    /**
     * 提醒项列表。每颗收藏卫星对应一条，记录下次过境信息。
     */
    private val _reminderItems = mutableStateOf(reminderStore.loadItems())
    val reminderItems: State<List<ReminderItem>> = _reminderItems

    /**
     * 提醒相关的一次性反馈消息（如"已自动添加提醒"）。null 表示无待显示消息。
     * UI 消费后调用 [consumeReminderFeedback] 清空。
     */
    private val _reminderFeedback = mutableStateOf<String?>(null)
    val reminderFeedback: State<String?> = _reminderFeedback

    fun consumeReminderFeedback() {
        _reminderFeedback.value = null
    }

    // ---- 天气模块 ----

    /**
     * 天气数据状态。null 表示尚未加载。
     */
    private val _weather = mutableStateOf<WeatherResult?>(weatherStore.load())
    val weather: State<WeatherResult?> = _weather

    /**
     * 天气加载状态。
     */
    private val _weatherLoading = mutableStateOf(false)
    val weatherLoading: State<Boolean> = _weatherLoading

    /**
     * 天气错误消息。null 表示无错误。
     */
    private val _weatherError = mutableStateOf<String?>(null)
    val weatherError: State<String?> = _weatherError

    private val _timeCardBackgroundFile = mutableStateOf<File?>(landscapeImageStore.effectiveImageFile)
    val timeCardBackgroundFile: State<File?> = _timeCardBackgroundFile

    private val _timeCardMaskColor = mutableStateOf(Color(ImageColorExtractor.DEFAULT_MASK_COLOR))
    val timeCardMaskColor: State<Color> = _timeCardMaskColor

    /**
     * 刷新天气数据。
     *
     * - 若定位不可用，设置错误提示并返回
     * - 若缓存有效（30 分钟内），不重复请求
     * - 网络请求失败时保留旧缓存，设置错误提示
     *
     * @param force true 表示忽略缓存强制刷新（用户手动触发）
     */
    fun refreshWeather(force: Boolean = false) {
        val result = _locationState.value.result
        if (result == null) {
            _weatherError.value = "需要定位权限才能获取天气"
            return
        }

        if (!force && weatherStore.isCacheValid()) {
            _weather.value = weatherStore.load()
            return
        }

        _weatherLoading.value = true
        _weatherError.value = null

        viewModelScope.launch {
            try {
                val weatherResult = weatherApiService.fetchWeather(
                    latitude = result.latitude,
                    longitude = result.longitude
                )
                weatherStore.save(weatherResult)
                _weather.value = weatherResult
                _weatherError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiKeyMissingException) {
                // API Key 未配置：构建时未注入 secret，无需重试
                _weatherError.value = "API Key 未配置，请运行 gradle encryptSecrets 生成 secrets.dat"
            } catch (e: WeatherNetworkException) {
                // 网络层失败：连接超时、断网、DNS 等
                _weatherError.value = "网络连接失败：${e.message}，请检查网络后重试"
            } catch (e: WeatherApiException) {
                // 高德 API 业务错误：Key 无效、配额超限、参数错误等
                _weatherError.value = "天气服务异常：${e.message}"
            } catch (e: Exception) {
                _weatherError.value = "天气加载失败：${e.message ?: "未知错误"}"
            } finally {
                _weatherLoading.value = false
            }
        }
    }

    /**
     * 启动天气定时刷新任务（每 30 分钟）。
     * 在 MainActivity 的 onCreate 中调用一次即可。
     */
    fun startWeatherAutoRefresh() {
        // Activity 重建（如旋转屏幕）会重复调用，Job 守卫防止启动多个刷新循环
        if (weatherAutoRefreshJob?.isActive == true) return
        weatherAutoRefreshJob = viewModelScope.launch {
            while (true) {
                if (_locationState.value.result != null) {
                    refreshWeather(force = false)
                }
                delay(WEATHER_REFRESH_INTERVAL_MS)
            }
        }
    }

    /**
     * 清除天气错误状态（UI 消费后调用）。
     */
    fun consumeWeatherError() {
        _weatherError.value = null
    }

    // ---- 每日一言模块 ----

    /**
     * 当前展示的每日一言文本。初始化时先填充本地兜底文案，避免空白；
     * 后台请求 hitokoto 成功后覆盖为网络结果（含来源）。
     */
    private val _dailyQuote = mutableStateOf(currentLocalFallbackQuote())
    val dailyQuote: State<String> = _dailyQuote

    // 记录已获取一言的日期（epoch day），同一天内不重复请求（持久化，进程重启后仍有效）
    private var dailyQuoteEpochDay: Long = settingsStore.dailyQuoteEpochDay

    /**
     * 刷新每日一言。
     *
     * - 同一天内（[dailyQuoteEpochDay] 匹配）不重复请求，避免频繁调用 API
     * - 先用本地兜底文案立即填充，后台异步请求网络结果
     * - 请求成功则更新为 "正文 —— 来源" 格式；失败则保留兜底文案
     *
     * 在 [initializeIfNeeded] 末尾调用，应用启动即拉取。
     */
    fun refreshDailyQuote() {
        val today = LocalDate.now()
        if (dailyQuoteEpochDay == today.toEpochDay()) return

        viewModelScope.launch {
            val quote = hitokotoApi.fetchQuote()
            if (quote != null) {
                _dailyQuote.value = quote.toDisplayText()
                dailyQuoteEpochDay = today.toEpochDay()
                settingsStore.dailyQuoteEpochDay = dailyQuoteEpochDay
            }
            // 失败时保留初始化时填入的本地兜底文案，不额外处理；
            // 不更新 dailyQuoteEpochDay，确保下次调用仍可重试
        }
    }

    fun refreshLandscapeImage() {
        // 优先用户自定义背景，否则回退到默认 the_moon 背景（确保默认文件已就绪）
        landscapeImageStore.ensureDefaultImage()
        val effectiveFile = landscapeImageStore.effectiveImageFile
        if (effectiveFile != null && effectiveFile.exists()) {
            _timeCardBackgroundFile.value = effectiveFile
            extractAndUpdateMaskColor(effectiveFile)
        } else {
            _timeCardBackgroundFile.value = null
        }
    }

    private fun extractAndUpdateMaskColor(imageFile: File) {
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = 4
                    }
                    android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath, options)
                }
                if (bitmap != null) {
                    _timeCardMaskColor.value = withContext(Dispatchers.IO) {
                        ImageColorExtractor.extractDominantColor(bitmap)
                    }
                    bitmap.recycle()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun clearCustomBackground() {
        landscapeImageStore.clearCustomImage()
        refreshLandscapeImage()
    }

    companion object {
        /**
         * 本地兜底文案池：网络不可用或请求失败时使用。
         * 按 dayOfYear 取模轮换，每日一句，保证本地环境下也有变化。
         */
        private val DAILY_QUOTES_FALLBACK = listOf(
            "保持热爱，奔赴山海。",
            "每一次发射，都是向未知的致敬。",
            "电波跨越山海，连接每一颗热爱星空的心。",
            "仰望星空，脚踏实地。",
            "卫星过境时分，是业余无线电人最美的时刻。",
            "千里之行，始于足下。",
            "心之所向，素履以往。"
        )

        /**
         * 根据当前日期取本地兜底文案。
         */
        private fun currentLocalFallbackQuote(): String {
            val today = LocalDate.now()
            return DAILY_QUOTES_FALLBACK[today.dayOfYear % DAILY_QUOTES_FALLBACK.size]
        }

        // 30 分钟自动刷新间隔
        private const val WEATHER_REFRESH_INTERVAL_MS = 30L * 60 * 1000

        // 地址解析去抖时长：位置停止变化 3 秒后才触发 Geocoder 反查，
        // 平衡实时性与性能（移动过程中持续触发会造成卡顿）
        private const val ADDRESS_DEBOUNCE_MS = 3_000L
        // 位置监听失败后的自动重试参数（指数退避）
        private const val LOCATION_RETRY_BASE_MS = 2_000L
        private const val LOCATION_RETRY_MAX_MS = 30_000L
    }

    /**
     * 切换某颗卫星的关注状态并持久化。
     *
     * 收藏时：自动从已预测的卫星列表中查找对应 [SatelliteInfo]，
     *         构造 [ReminderItem] 写入 [ReminderStore] 并调度闹钟；
     *         若该卫星暂无过境预测，则仅记录反馈提示。
     * 取消收藏时：删除对应提醒项并取消闹钟。
     */
    fun toggleFavorite(catalogNumber: Int) {
        val updated = favoriteStore.toggle(catalogNumber)
        _favoriteSatellites.value = updated

        val nowFavorite = catalogNumber in updated
        if (nowFavorite) {
            // 收藏：查找已预测的卫星过境信息，自动创建提醒
            val satInfo = _satelliteState.value.satellites.firstOrNull { it.catalogNumber == catalogNumber }
            if (satInfo != null && !satInfo.isCurrentlyVisible) {
                // 仅对未来过境创建提醒（在境卫星 AOS 已过去）
                addReminderForSatellite(satInfo)
                _reminderFeedback.value = "已收藏 ${satInfo.name}，自动添加过境提醒"
            } else if (satInfo != null && satInfo.isCurrentlyVisible) {
                // 当前在境，无法创建提醒，但不阻塞收藏
                _reminderFeedback.value = "已收藏 ${satInfo.name}，当前在境，下次过境预测后将自动添加提醒"
            } else {
                _reminderFeedback.value = "已收藏，暂无过境预测数据，将在下次刷新后自动添加提醒"
            }
        } else {
            // 取消收藏：删除提醒
            reminderStore.removeItem(catalogNumber)
            reminderScheduler.cancel(catalogNumber)
            _reminderItems.value = reminderStore.loadItems()
            _reminderFeedback.value = "已取消收藏并移除提醒"
        }
    }

    /**
     * 将单颗卫星的过境信息转换为 [ReminderItem] 并写入存储 + 调度闹钟。
     */
    private fun addReminderForSatellite(sat: SatelliteInfo) {
        val settings = _reminderSettings.value
        val item = ReminderItem(
            catalogNumber = sat.catalogNumber,
            name = sat.name,
            aosTimeMillis = sat.aosTime.toEpochMilli(),
            losTimeMillis = sat.losTime.toEpochMilli(),
            maxElevation = sat.maxElevation,
            aosAzimuth = sat.aosAzimuth,
            losAzimuth = sat.losAzimuth,
            modes = sat.modes,
            enabled = true
        )
        reminderStore.upsertItem(item)
        reminderScheduler.schedule(item, settings)
        _reminderItems.value = reminderStore.loadItems()
    }

    /**
     * 更新提醒设置并重新调度所有提醒。
     */
    fun updateReminderSettings(settings: ReminderSettings) {
        reminderStore.saveSettings(settings)
        _reminderSettings.value = settings
        // 声音/振动设置变化时通知渠道不可直接改 importance，
        // 必须 deleteNotificationChannel + 重建才能让新设置立即生效。
        ReminderNotificationHelper(app).recreateChannel(settings)
        // 重新调度所有提醒（设置变更可能影响触发时间或是否调度）
        reminderScheduler.scheduleAll(_reminderItems.value, settings)
    }

    /**
     * 切换单个提醒项的启用状态。
     */
    fun setReminderItemEnabled(catalogNumber: Int, enabled: Boolean) {
        val updatedList = reminderStore.setItemEnabled(catalogNumber, enabled)
        _reminderItems.value = updatedList
        val item = updatedList.firstOrNull { it.catalogNumber == catalogNumber } ?: return
        if (enabled) {
            reminderScheduler.schedule(item, _reminderSettings.value)
        } else {
            reminderScheduler.cancel(catalogNumber)
        }
    }

    /**
     * 手动删除单个提醒项（不取消收藏，仅停止提醒）。
     */
    fun deleteReminderItem(catalogNumber: Int) {
        reminderStore.removeItem(catalogNumber)
        reminderScheduler.cancel(catalogNumber)
        _reminderItems.value = reminderStore.loadItems()
    }

    val hasLocationPermission: Boolean
        get() = locationHelper.hasPermission()

    /**
     * ViewModel 初始化：从本地缓存加载 TLE，并按需触发后台更新。
     * 应在 UI 准备好后调用一次。多次调用安全（仅首次执行）。
     *
     * 缓存读取（JSON 解析）放在 IO 调度器执行，避免阻塞主线程导致 UI 卡顿。
     */
    suspend fun initializeIfNeeded() {
        if (initialized) return
        initialized = true

        // 并行从本地缓存恢复 TLE 与预测结果（IO 密集型，避免在主线程解析 JSON）
        val cachedTle = withContext(Dispatchers.IO) { satelliteCache.load() }
        val cachedPredict = withContext(Dispatchers.IO) { predictCache.load() }

        if (cachedTle != null) {
            _satelliteState.value = _satelliteState.value.copy(
                cachedTles = cachedTle.tles,
                lastSatelliteUpdateTime = cachedTle.updatedAt
            )
        }

        // 加载转发器频率库（SatNOGS）：供卫星列表展示多收发器/频率/状态，
        // 并决定过境预测范围（只对有活跃转发器/目录的卫星预测）。
        // 必须先于首次预测完成，否则预测范围会退化为仅目录卫星。
        // 静默失败（网络不可用时仅保留本地缓存或空数据），不阻塞主流程。
        try {
            if (settingsStore.useSatnogsTransmitters) {
                _radioMap.value = radioInfoRepository.getAllRadios()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 转发器库加载失败不影响其它功能
        }

        // 优先用预测缓存即时回填：坐标接近 + 2h 内有效 → 直接显示，跳过 SGP4
        val current = _locationState.value.result
        if (current != null && cachedPredict != null &&
            cachedPredict.matchesLocation(current.latitude, current.longitude) &&
            cachedPredict.isFresh()
        ) {
            // 回填时剔除已过期过境（LOS < now），保留新鲜数据
            val now = Instant.now()
            val fresh = cachedPredict.satellites.filter { it.losTime.isAfter(now) }
            if (fresh.isNotEmpty()) {
                _satelliteState.value = _satelliteState.value.copy(
                    isSatelliteLoading = false,
                    satellites = fresh,
                    satelliteError = null
                )
                // 同步内存缓存，使短周期去重也生效
                cachedPredictionSatellites = fresh
                cachedPredictionLat = cachedPredict.latitude
                cachedPredictionLon = cachedPredict.longitude
                cachedPredictionTime = cachedPredict.predictedAt
                // 后台异步重新预测纠偏（拉最新 TLE 后覆盖），不阻塞 UI
                triggerPrediction(current.latitude, current.longitude)
            } else if (cachedTle != null) {
                triggerPrediction(current.latitude, current.longitude)
            }
        } else if (current != null && cachedTle != null) {
            // 有定位 + 有 TLE 但无可用预测缓存：正常预测
            triggerPrediction(current.latitude, current.longitude)
        }

        // TLE 缓存为空或已过期：后台拉取新数据
        if (cachedTle == null || isSatelliteSourceExpired(cachedTle.updatedAt)) {
            refreshSatelliteSourceOnly()
        }

        // 启动 AMSAT 状态定时抓取（5 分钟一次），驱动状态持续显示与延续逻辑
        statusTracker.start()

        // 拉取每日一言（hitokoto），失败回退本地文案
        refreshDailyQuote()

        // 加载时间卡片背景图
        refreshLandscapeImage()

        // 加载课程进度
        loadAllCourseProgress()
    }

    fun refreshLocation() {
        if (!locationHelper.hasPermission()) {
            _locationState.value = _locationState.value.copy(
                isLoading = false,
                error = "需要定位权限"
            )
            return
        }

        // 取消上一次刷新任务，避免并发竞态导致 UI 闪烁和数据不一致
        refreshJob?.cancel()
        locationOnlyJob?.cancel()

        // 注意：不清空已有 result，避免刷新过程中 AMapCard/ZoneInfoCard 消失导致 UI 闪烁，
        // 也避免定位失败时丢失上一次的有效位置（与 refreshLocationOnly 行为一致）。
        _locationState.value = _locationState.value.copy(
            isLoading = true,
            error = null
        )
        refreshJob = viewModelScope.launch {
            try {
                val location = locationHelper.getCurrentLocation()
                val zoneInfo = ZoneResolver.resolve(location.latitude, location.longitude)

                // 立即显示定位结果（不等待地址），减少用户感知等待时间
                val baseResult = LocationResult(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    cqZone = zoneInfo.cqZone,
                    ituZone = zoneInfo.ituZone,
                    maidenhead = zoneInfo.maidenhead,
                    address = ""
                )
                _locationState.value = _locationState.value.copy(
                    isLoading = false,
                    result = baseResult,
                    lastLocationUpdateTime = Instant.now(),
                    lastLocationCity = ""
                )

                // 持久化位置坐标，供后台 Worker 做过境预测时使用
                settingsStore.lastLatitude = location.latitude
                settingsStore.lastLongitude = location.longitude

                // 后台加载地址
                val addressDeferred = async {
                    locationHelper.getAddress(location.latitude, location.longitude)
                }
                val cityDeferred = async {
                    locationHelper.getCityAddress(location.latitude, location.longitude)
                }

                val address = addressDeferred.await()
                val city = cityDeferred.await()

                _locationState.value = _locationState.value.copy(
                    result = baseResult.copy(address = address),
                    lastLocationCity = city
                )

                // 触发卫星过境预测（基于已有 TLE 缓存或刚拉取的 TLE）
                triggerPrediction(location.latitude, location.longitude)

                // 触发天气数据刷新（基于新定位）
                refreshWeather()

                // 首次成功定位后启动持续位置监听，跟踪设备移动
                startContinuousLocationUpdates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _locationState.value = _locationState.value.copy(
                    isLoading = false,
                    error = e.message ?: "定位失败"
                )
            }
        }
    }

    /**
     * 启动持续位置监听。
     *
     * - 已在监听时直接返回，避免重复注册
     * - 无权限时不启动
     * - 收到新位置时立即更新经纬度 + CQ/ITU/Maidenhead（本地计算极快）
     * - 地址（Geocoder 反查）采用去抖策略：3 秒内无新位置变化才解析，
     *   避免移动过程中频繁调用 Geocoder 造成性能压力
     * - 监听异常时通过 [retryWhen] 指数退避自动重启，避免瞬时故障导致
     *   持续监听静默死亡；每次重试均通过 [uiState] 反馈状态
     *
     * 在 [refreshLocation] / [refreshLocationOnly] 成功后自动调用。
     * ViewModel 销毁时由 viewModelScope 自动取消，[onCleared] 中显式清理。
     */
    fun startContinuousLocationUpdates() {
        if (locationUpdatesJob?.isActive == true) return
        if (!locationHelper.hasPermission()) return

        locationUpdatesJob = viewModelScope.launch {
            // 重试退避计数：每次成功收到位置后归零，失败时指数增长
            var retryAttempt = 0
            locationHelper.locationUpdates()
                .retryWhen { cause, _ ->
                    // 协程取消不重试，直接向外抛出以终止监听
                    if (cause is CancellationException) return@retryWhen false
                    val backoff = (LOCATION_RETRY_BASE_MS shl retryAttempt.coerceAtMost(4))
                        .coerceAtMost(LOCATION_RETRY_MAX_MS)
                    retryAttempt++
                    _locationState.value = _locationState.value.copy(
                        error = "位置监听中断：${cause.message ?: "未知错误"}，${backoff / 1000}s 后自动恢复"
                    )
                    delay(backoff)
                    // 退避完成后重新订阅上游 Flow，重新注册定位回调
                    true
                }
                .collect { location ->
                    // 1. 立即更新经纬度 + zone 信息（本地计算，毫秒级）
                    val zoneInfo = ZoneResolver.resolve(location.latitude, location.longitude)
                    val previous = _locationState.value.result
                    val updated = LocationResult(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        cqZone = zoneInfo.cqZone,
                        ituZone = zoneInfo.ituZone,
                        maidenhead = zoneInfo.maidenhead,
                        // 保留旧地址，等去抖后再覆盖
                        address = previous?.address ?: ""
                    )
                    _locationState.value = _locationState.value.copy(
                        isLoading = false,
                        result = updated,
                        lastLocationUpdateTime = Instant.now(),
                        // 监听恢复正常，清空之前的重试错误提示
                        error = null
                    )
                    // 持久化位置坐标，供后台 Worker 做过境预测时使用
                    settingsStore.lastLatitude = location.latitude
                    settingsStore.lastLongitude = location.longitude
                    // 收到有效位置，重置退避计数
                    retryAttempt = 0

                    // 2. 地址解析去抖：3 秒内无新位置才解析（移动停止后补全地址）
                    addressDebounceJob?.cancel()
                    addressDebounceJob = viewModelScope.launch {
                        delay(ADDRESS_DEBOUNCE_MS)
                        val address = locationHelper.getAddress(location.latitude, location.longitude)
                        val city = locationHelper.getCityAddress(location.latitude, location.longitude)
                        val current = _locationState.value.result
                        if (current != null &&
                            current.latitude == location.latitude &&
                            current.longitude == location.longitude
                        ) {
                            _locationState.value = _locationState.value.copy(
                                result = current.copy(address = address),
                                lastLocationCity = city
                            )
                        }
                    }
                }
        }
    }

    /**
     * 显式停止持续位置监听。可用于用户主动暂停或权限被撤销时。
     */
    fun stopContinuousLocationUpdates() {
        locationUpdatesJob?.cancel()
        locationUpdatesJob = null
        addressDebounceJob?.cancel()
        addressDebounceJob = null
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope 已会自动取消所有子协程，这里显式取消便于状态归零
        stopContinuousLocationUpdates()
        statusTracker.stop()
        cwPlayer.stop()
    }

    /**
     * 仅刷新定位（不重新获取卫星数据）。用于卫星页"获取定位"按钮。
     * 成功后保留已有卫星列表，但会用新定位重新预测过境。
     */
    fun refreshLocationOnly() {
        if (!locationHelper.hasPermission()) {
            _locationState.value = _locationState.value.copy(
                error = "需要定位权限"
            )
            return
        }

        locationOnlyJob?.cancel()
        _locationState.value = _locationState.value.copy(
            isLoading = true,
            error = null
        )
        locationOnlyJob = viewModelScope.launch {
            try {
                val location = locationHelper.getCurrentLocation()
                val zoneInfo = ZoneResolver.resolve(location.latitude, location.longitude)
                val city = locationHelper.getCityAddress(location.latitude, location.longitude)
                val address = locationHelper.getAddress(location.latitude, location.longitude)

                val newResult = LocationResult(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    cqZone = zoneInfo.cqZone,
                    ituZone = zoneInfo.ituZone,
                    maidenhead = zoneInfo.maidenhead,
                    address = address
                )
                _locationState.value = _locationState.value.copy(
                    isLoading = false,
                    result = newResult,
                    lastLocationUpdateTime = Instant.now(),
                    lastLocationCity = city
                )
                // 持久化位置坐标，供后台 Worker 做过境预测时使用
                settingsStore.lastLatitude = location.latitude
                settingsStore.lastLongitude = location.longitude
                // 用新定位重新预测
                triggerPrediction(location.latitude, location.longitude)

                // 首次成功定位后启动持续位置监听（与 refreshLocation 行为一致）
                startContinuousLocationUpdates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _locationState.value = _locationState.value.copy(
                    isLoading = false,
                    error = e.message ?: "定位失败"
                )
            }
        }
    }

    /**
     * 仅刷新卫星源（不重新定位）。用于卫星页"更新卫星源"按钮。
     *
     * 乐观先行：点击瞬间用现有缓存就地回显（有定位则立即重算预测，命中 15 分钟
     * 预测缓存时 loading 瞬间结束），UI 在 100ms 内完成响应，与网络无关。
     * 最新 TLE 在后台从 tle.hamkit.click CDN 静默拉取后替换结果，不打断用户。
     * 无定位时也允许更新：只更新 TLE 缓存，等有定位后再做预测。
     */
    fun refreshSatelliteSourceOnly() {
        satelliteOnlyJob?.cancel()

        val cachedTles = _satelliteState.value.cachedTles
        val current = _locationState.value.result

        // ── 乐观先行（同步，<100ms）──
        // 有缓存 TLE 时先用它回显，让 UI 立即有反应，不等待网络。
        if (cachedTles.isNotEmpty()) {
            if (current != null) {
                // 有定位：立即用缓存 TLE 触发预测。
                // triggerPrediction 内部命中 15 分钟预测缓存时跳过 SGP4 直接回填，
                // loading 瞬间结束；未命中则后台协程计算，UI 已感知到响应。
                _satelliteState.value = _satelliteState.value.copy(satelliteError = null)
                triggerPrediction(current.latitude, current.longitude, cachedTles)
            } else {
                // 无定位：直接回显缓存列表，结束 loading
                _satelliteState.value = _satelliteState.value.copy(
                    isSatelliteLoading = false,
                    cachedTles = cachedTles,
                    satelliteError = null,
                    lastSatelliteUpdateTime = Instant.now()
                )
            }
        } else {
            // 首次使用无缓存：无法乐观，只能等后台拉取
            _satelliteState.value = _satelliteState.value.copy(
                isSatelliteLoading = true,
                satelliteError = null
            )
        }

        // ── 后台刷新：从 tle.hamkit.click CDN 拉取最新 TLE，到货后静默替换 ──
        satelliteOnlyJob = viewModelScope.launch {
            try {
                val tles = fetchAndCacheTLEs()
                // 同步刷新转发器频率库（与 TLE 源同源更新）
                if (settingsStore.useSatnogsTransmitters) {
                    radioInfoRepository.refresh()
                    _radioMap.value = radioInfoRepository.getAllRadios()
                }
                val loc = _locationState.value.result
                if (loc != null) {
                    // 有定位：用最新 TLE 重新预测，结果静默替换 UI
                    triggerPrediction(loc.latitude, loc.longitude, tles)
                } else {
                    // 无定位：仅更新缓存，等待定位后再预测
                    _satelliteState.value = _satelliteState.value.copy(
                        isSatelliteLoading = false,
                        cachedTles = tles,
                        lastSatelliteUpdateTime = Instant.now()
                    )
                    // 即便尚未预测，也可先拉取分段运行状态供展示
                    refreshSegmentStatuses()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 乐观已回显则不打断用户；无缓存才置 error
                if (cachedTles.isEmpty()) {
                    _satelliteState.value = _satelliteState.value.copy(
                        isSatelliteLoading = false,
                        satelliteError = e.message ?: "卫星源更新失败"
                    )
                }
            }
        }
    }

    /**
     * 拉取并聚合各卫星的 BJT 分段运行状态（含延续逻辑），结果回填 [SatelliteUiState.segmentStatuses]。
     *
     * 为目录中所有有 AMSAT 名称的卫星并行请求 sat_info.php，单星失败不影响其它卫星。
     * 1 小时内不重复拉取，避免对 AMSAT 服务器造成压力。
     */
    fun refreshSegmentStatuses() {
        segmentStatusFetchedAt?.let {
            if (Duration.between(it, Instant.now()).toMinutes() < 60) return
        }
        segmentStatusJob?.cancel()
        segmentStatusJob = viewModelScope.launch {
            val namesByCat = SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER
            val results: Map<Int, List<SegmentStatus>> = coroutineScope {
                namesByCat.map { (catNum, amsatName) ->
                    async {
                        val reports = try {
                            amsatStatusApi.fetchStatusReports(amsatName)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (reports != null) {
                            catNum to SatelliteStatusSegmenter.buildSegmentTimeline(reports)
                        } else {
                            null
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            segmentStatusFetchedAt = Instant.now()
            _satelliteState.value = _satelliteState.value.copy(segmentStatuses = results)
        }
    }

    /**
     * 拉取 TLE 并写入本地缓存，返回最新 TLE 列表。
     * active 是完整 16k+ 卫星目录的来源，必须始终启用；历史版本可能将其
     * SharedPreferences 值持久化为 false，不能让旧值导致更新退回约 600 颗。
     * 缓存写入（JSON 序列化）放在 IO 调度器执行，避免阻塞主线程。
     */
    private suspend fun fetchAndCacheTLEs(): List<SourcedTLE> {
        val tles = satelliteDataSource.fetchAmateurTLEs(
            enableAmateur = settingsStore.tleSourceAmateur,
            enableSatnogs = settingsStore.tleSourceSatnogs,
            enableActive = true
        )
        val now = Instant.now()
        withContext(Dispatchers.IO) { satelliteCache.save(tles, now) }
        return tles
    }

    /**
     * 触发卫星过境预测。使用 [tlesOverride] 或当前缓存的 TLE。
     */
    private fun triggerPrediction(
        latitude: Double,
        longitude: Double,
        tlesOverride: List<SourcedTLE>? = null
    ) {
        val tles = tlesOverride ?: _satelliteState.value.cachedTles
        predictJob?.cancel()
        if (tles.isEmpty()) {
            // 无 TLE 数据，先拉取再预测
            predictJob = viewModelScope.launch {
                try {
                    val fresh = fetchAndCacheTLEs()
                    predictAndApply(fresh, latitude, longitude)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _satelliteState.value = _satelliteState.value.copy(
                        isSatelliteLoading = false,
                        satelliteError = e.message ?: "卫星源更新失败"
                    )
                }
            }
            return
        }
        predictJob = viewModelScope.launch {
            predictAndApply(tles, latitude, longitude)
        }
    }

    private suspend fun predictAndApply(
        tles: List<SourcedTLE>,
        latitude: Double,
        longitude: Double
    ) {
        try {
            // 检查预测结果缓存：同一坐标（±0.001°）+ 15 分钟内有效 → 跳过 SGP4 计算
            val cached = cachedPredictionSatellites
            val cachedTime = cachedPredictionTime
            if (cached != null && cachedTime != null &&
                Math.abs(latitude - cachedPredictionLat) < 0.001 &&
                Math.abs(longitude - cachedPredictionLon) < 0.001 &&
                java.time.Duration.between(cachedTime, Instant.now()).toMinutes() < 15
            ) {
                _satelliteState.value = _satelliteState.value.copy(
                    isSatelliteLoading = false,
                    satellites = cached,
                    cachedTles = tles,
                    satelliteError = null,
                    lastSatelliteUpdateTime = cachedTime
                )
                return
            }

            _satelliteState.value = _satelliteState.value.copy(isSatelliteLoading = true)
            // 预测范围：仅对"有活跃转发器数据 OR 在硬编码目录中"的卫星计算过境。
            // active 源全量可达 9000+ 颗（含 Starlink 等非业余卫星），全量预测
            // 会长时间占用 CPU；列表展示仍用全量 TLE（卫星太少问题），
            // 无转发器数据的卫星仅显示信息、不预测过境。
            val predictable = filterPredictableTles(tles)
            val satellites = withContext(Dispatchers.Default) {
                satellitePredictor.predictUpcomingPasses(
                    sourcedTles = predictable,
                    latitude = latitude,
                    longitude = longitude
                )
            }
            // 更新预测缓存
            val predictedAt = Instant.now()
            cachedPredictionSatellites = satellites
            cachedPredictionLat = latitude
            cachedPredictionLon = longitude
            cachedPredictionTime = predictedAt
            // 持久化预测结果：进程重启后可即时回填，避免重复 SGP4
            withContext(Dispatchers.IO) {
                predictCache.save(satellites, latitude, longitude, predictedAt)
            }

            _satelliteState.value = _satelliteState.value.copy(
                isSatelliteLoading = false,
                satellites = satellites,
                cachedTles = tles,
                satelliteError = null,
                lastSatelliteUpdateTime = predictedAt
            )
            // 预测完成后刷新所有收藏卫星的提醒项
            refreshRemindersFromPrediction(satellites)
            // 预测完成后异步拉取 BJT 分段运行状态（含延续逻辑）
            refreshSegmentStatuses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _satelliteState.value = _satelliteState.value.copy(
                isSatelliteLoading = false,
                satelliteError = e.message ?: "卫星过境预测失败"
            )
        }
    }

    /**
     * 过滤出需要做过境预测的 TLE：有活跃转发器（SatNOGS 频率库）或
     * 在硬编码 [SatelliteCatalog] 中的卫星。
     *
     * 转发器库尚未加载（空 map）时回退为目录集合，保证至少对已知业余卫星预测；
     * 返回空列表时调用方按"无可用预测目标"处理。
     */
    private fun filterPredictableTles(tles: List<SourcedTLE>): List<SourcedTLE> {
        if (tles.isEmpty()) return emptyList()
        val radios = _radioMap.value
        val catalogIds = SatelliteCatalog.catalogNumbers
        return tles.filter { stle ->
            val catnum = stle.tle.catnum
            catnum in catalogIds ||
                radios[catnum].orEmpty().any { it.isActive }
        }
    }

    /**
     * 基于最新预测结果刷新收藏卫星的提醒项。
     *
     * - 对每颗已收藏且在未来有过境的卫星：更新或新建 [ReminderItem] 并重新调度
     * - 对当前在境的卫星：跳过（AOS 已过去）
     * - 对已不在预测列表中的收藏卫星：保留旧提醒项不动（避免预测窗口外的卫星被清空）
     */
    private fun refreshRemindersFromPrediction(satellites: List<SatelliteInfo>) {
        val favorites = _favoriteSatellites.value
        if (favorites.isEmpty()) return

        val settings = _reminderSettings.value
        // 仅对未来过境创建提醒：AOS 必须在未来。
        // 旧逻辑用 !isCurrentlyVisible 过滤，但预测器对在境卫星返回的是下次过境，
        // 旧过滤会把下次过境一起丢弃。改以 AOS 时间为准。
        val nowMillis = java.lang.System.currentTimeMillis()
        val futureFavorites = satellites.filter {
            it.catalogNumber in favorites && it.aosTime.toEpochMilli() > nowMillis
        }
        if (futureFavorites.isEmpty()) return

        val updatedItems = reminderStore.loadItems().toMutableList()
        var changed = false
        futureFavorites.forEach { sat ->
            val item = ReminderItem(
                catalogNumber = sat.catalogNumber,
                name = sat.name,
                aosTimeMillis = sat.aosTime.toEpochMilli(),
                losTimeMillis = sat.losTime.toEpochMilli(),
                maxElevation = sat.maxElevation,
                aosAzimuth = sat.aosAzimuth,
                losAzimuth = sat.losAzimuth,
                modes = sat.modes,
                enabled = true
            )
            val idx = updatedItems.indexOfFirst { it.catalogNumber == sat.catalogNumber }
            if (idx >= 0) {
                // 保留原 enabled 状态，仅更新过境信息
                val merged = item.copy(enabled = updatedItems[idx].enabled)
                updatedItems[idx] = merged
            } else {
                updatedItems.add(item)
            }
            changed = true
        }
        if (changed) {
            reminderStore.saveItems(updatedItems)
            _reminderItems.value = reminderStore.loadItems()
            reminderScheduler.scheduleAll(_reminderItems.value, settings)
        }
    }

    fun dismissError() {
        _locationState.value = _locationState.value.copy(error = null)
    }

    // ---- CW练习方法 ----

    fun updateCWSettings(settings: CWSettings) {
        _cwSettings.value = settings
        viewModelScope.launch {
            cwSettingsStore.updateSettings(settings)
        }
    }

    fun generateCWPracticeText() {
        _currentCourseId.value = 0
        _currentLessonId.value = 0
        // 清除课程练习残留的标题与课时信息，避免自由练习页显示旧课程状态
        _currentCourseTitle.value = ""
        _currentLessonInfo.value = ""
        val settings = _cwSettings.value
        val text = cwGenerator.generateRandomCharacters(settings.characterSet, settings.practiceLength)
        _cwCurrentText.value = text
        _cwMorseCode.value = cwGenerator.toMorseCode(text)
        // 清空用户输入框，提供干净的学习环境
        _cwUserInput.value = ""
        _cwAccuracy.value = 0f
    }

    fun startCWPractice() {
        if (_cwIsPlaying.value) return
        if (_cwMorseCode.value.isEmpty()) return

        _cwIsPlaying.value = true
        _cwIsPaused.value = false
        _cwUserInput.value = ""

        val settings = _cwSettings.value
        cwPlayer.playMorseCode(
            morseCode = _cwMorseCode.value,
            wpm = settings.wpm,
            frequency = settings.frequency,
            playMode = settings.playMode,
            onComplete = {
                _cwIsPlaying.value = false
            }
        )
    }

    fun pauseCWPractice() {
        if (!_cwIsPlaying.value) return
        _cwIsPaused.value = true
        cwPlayer.pause()
    }

    fun resumeCWPractice() {
        if (!_cwIsPlaying.value || !_cwIsPaused.value) return
        _cwIsPaused.value = false
        cwPlayer.resume()
    }

    fun stopCWPractice() {
        _cwIsPlaying.value = false
        _cwIsPaused.value = false
        cwPlayer.stop()
    }

    fun updateCWUserInput(input: String) {
        _cwUserInput.value = input
    }

    fun checkCWResults() {
        val currentText = _cwCurrentText.value
        val userInput = _cwUserInput.value

        if (currentText.isEmpty() || userInput.isEmpty()) {
            _cwAccuracy.value = 0f
            return
        }

        // 大小写不敏感比较：忽略大小写差异，只要字符本身匹配即判定正确。
        // 分母取两者较长长度，多输入/少输入的字符均计为错误
        val correctCount = currentText.zip(userInput).count { (a, b) -> 
            a.equals(b, ignoreCase = true) 
        }
        val maxLen = maxOf(currentText.length, userInput.length)
        val accuracy = correctCount.toFloat() / maxLen.toFloat() * 100f
        _cwAccuracy.value = accuracy

        viewModelScope.launch {
            val progress = CWProgress(
                courseId = _currentCourseId.value,
                lessonId = _currentLessonId.value,
                completedAt = System.currentTimeMillis(),
                accuracy = accuracy,
                wpm = _cwSettings.value.wpm,
                duration = _cwSettings.value.practiceDuration
            )
            cwProgressStore.insertProgress(progress)

            // 更新课程进度
            if (accuracy >= 80f) { // 80%以上算通过
                advanceCourseProgress()
            }
        }
    }

    // ---- 课程进度跟踪 ----

    private val _currentCourseId = MutableStateFlow(0)
    val currentCourseId: StateFlow<Int> = _currentCourseId.asStateFlow()

    private val _currentLessonId = MutableStateFlow(0)
    val currentLessonId: StateFlow<Int> = _currentLessonId.asStateFlow()

    private val _currentCourseTitle = MutableStateFlow("")
    val currentCourseTitle: StateFlow<String> = _currentCourseTitle.asStateFlow()

    private val _currentLessonInfo = MutableStateFlow("")
    val currentLessonInfo: StateFlow<String> = _currentLessonInfo.asStateFlow()

    private val _courseProgress = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val courseProgress: StateFlow<Map<Int, Float>> = _courseProgress.asStateFlow()

    private val courseNames = mapOf(
        1 to "Koch课程",
        2 to "字符组练习",
        3 to "呼号训练",
        4 to "文本训练"
    )

    fun generateTutorialText(lessonId: Int) {
        _currentCourseId.value = lessonId
        _currentLessonId.value = 1
        _currentCourseTitle.value = courseNames[lessonId] ?: "教程练习"

        // 从数据库加载该课程的最大已完成课时，智能定位到未完成的课程
        viewModelScope.launch {
            val maxCompletedLesson = cwProgressStore.getMaxCompletedLessonId(lessonId)
            val nextLesson = if (maxCompletedLesson != null && maxCompletedLesson > 0) {
                // 定位到下一个未完成的课程
                val maxLessons = getMaxLessonsForCourse(lessonId)
                (maxCompletedLesson + 1).coerceAtMost(maxLessons)
            } else {
                1 // 从第1课开始
            }

            _currentLessonId.value = nextLesson
            val text = cwGenerator.getTutorialContent(courseId = lessonId, lessonId = nextLesson, length = 25)
            _cwCurrentText.value = text
            _cwMorseCode.value = cwGenerator.toMorseCode(text)
            // 清空用户输入框，提供干净的学习环境
            _cwUserInput.value = ""
            _cwAccuracy.value = 0f
            updateLessonInfo()

            // 加载课程进度
            loadCourseProgress()
        }
    }

    private fun getMaxLessonsForCourse(courseId: Int): Int {
        return when (courseId) {
            1 -> 26 // Koch课程26个字符
            2, 3, 4 -> 10 // 其他课程10组
            else -> 1
        }
    }

    private suspend fun loadCourseProgress() {
        val progressMap = mutableMapOf<Int, Float>()
        for (courseId in 1..4) {
            val maxLessons = getMaxLessonsForCourse(courseId)
            val completedCount = cwProgressStore.getCompletedLessonCount(courseId)
            val progress = (completedCount.toFloat() / maxLessons).coerceIn(0f, 1f)
            progressMap[courseId] = progress
        }
        _courseProgress.value = progressMap
    }

    private fun updateLessonInfo() {
        val courseId = _currentCourseId.value
        val lessonId = _currentLessonId.value

        _currentLessonInfo.value = when (courseId) {
            1 -> "Koch课程 第${lessonId}课 - 学习字符: ${cwGenerator.getKochLessonChars(lessonId)}"
            2 -> "字符组练习 第${lessonId}组 - 3字符组合"
            3 -> "呼号训练 第${lessonId}组 - 10个呼号"
            4 -> "文本训练 第${lessonId}组 - CW通联文本"
            else -> ""
        }
    }

    private fun advanceCourseProgress() {
        val courseId = _currentCourseId.value
        val lessonId = _currentLessonId.value

        if (courseId <= 0) return

        val maxLessons = getMaxLessonsForCourse(courseId)

        if (lessonId < maxLessons) {
            _currentLessonId.value = lessonId + 1
            val text = cwGenerator.getTutorialContent(
                courseId = courseId,
                lessonId = lessonId + 1,
                length = 25
            )
            _cwCurrentText.value = text
            _cwMorseCode.value = cwGenerator.toMorseCode(text)
            // 清空用户输入框，提供干净的学习环境
            _cwUserInput.value = ""
            _cwAccuracy.value = 0f
            updateLessonInfo()
        }

        // 重新加载课程进度
        viewModelScope.launch {
            loadCourseProgress()
        }
    }

    fun resetCourseProgress(courseId: Int) {
        _currentCourseId.value = courseId
        _currentLessonId.value = 1
        _courseProgress.value = _courseProgress.value.toMutableMap().apply {
            put(courseId, 0f)
        }
        generateTutorialText(courseId)
    }

    fun loadAllCourseProgress() {
        viewModelScope.launch {
            loadCourseProgress()
        }
    }
}

/**
 * 卫星源过期阈值：TLE 数据通常 24 小时后视为过期。
 */
private val SATELLITE_SOURCE_EXPIRY = java.time.Duration.ofHours(24)

/**
 * 判断 [lastUpdate] 相对 [now] 是否已过期。
 */
fun isSatelliteSourceExpired(lastUpdate: Instant?, now: Instant = Instant.now()): Boolean {
    if (lastUpdate == null) return true
    return java.time.Duration.between(lastUpdate, now) >= SATELLITE_SOURCE_EXPIRY
}

/**
 * 卫星筛选条件。
 *
 * @param modes 工作模式多选筛选，空集合表示不按模式筛选。可选值：FM/SSTV/DSTAR/CW/USB/LSB/""(未知)
 * @param nameQuery 名称搜索关键词，大小写不敏感匹配卫星名称或 NORAD 编号，空字符串表示不搜索
 * @param onlyUpcoming 仅显示即将入境（不含当前在境）
 * @param onlyInPass 仅显示当前在境
 * @param onlyAmsat 仅显示 AMSAT 状态 API 中的卫星（即有 status 报告的）
 * @param onlyFavorites 仅显示已关注卫星
 */
data class SatelliteFilter(
    val modes: Set<String> = emptySet(),
    val nameQuery: String = "",
    val onlyUpcoming: Boolean = false,
    val onlyInPass: Boolean = false,
    val onlyAmsat: Boolean = false,
    val onlyFavorites: Boolean = false
) {
    /**
     * 当前筛选是否处于激活状态（任一条件被设置）。
     */
    val isActive: Boolean
        get() = modes.isNotEmpty() || nameQuery.isNotBlank() ||
            onlyUpcoming || onlyInPass || onlyAmsat || onlyFavorites
}

/**
 * 应用筛选条件到卫星列表（[SatelliteListItem] 版本，基于转发器模式）。
 */
fun List<SatelliteListItem>.applyFilterToItems(
    filter: SatelliteFilter,
    favorites: Set<Int> = emptySet()
): List<SatelliteListItem> {
    if (!filter.isActive) return this
    val query = filter.nameQuery.trim()
    return this.filter { sat ->
        // 模式匹配：选""表示匹配未知模式（effectiveModes 为空）
        val modeOk = filter.modes.isEmpty() || filter.modes.any { mode ->
            if (mode.isEmpty()) sat.effectiveModes.isEmpty() else mode in sat.effectiveModes
        }
        val nameOk = query.isBlank() ||
            sat.name.contains(query, ignoreCase = true) ||
            sat.catalogNumber.toString().contains(query)
        val upcomingOk = !filter.onlyUpcoming || !sat.isCurrentlyVisible
        val inPassOk = !filter.onlyInPass || sat.isCurrentlyVisible
        val amsatOk = !filter.onlyAmsat || sat.status.isNotBlank()
        val favoriteOk = !filter.onlyFavorites || sat.catalogNumber in favorites
        modeOk && nameOk && upcomingOk && inPassOk && amsatOk && favoriteOk
    }
}

/**
 * 应用筛选条件到卫星列表。
 */
fun List<SatelliteInfo>.applyFilter(
    filter: SatelliteFilter,
    favorites: Set<Int> = emptySet()
): List<SatelliteInfo> {
    if (!filter.isActive) return this
    val query = filter.nameQuery.trim()
    return this.filter { sat ->
        // 模式匹配：选""表示匹配未知模式（modes 为空）
        val modeOk = filter.modes.isEmpty() || filter.modes.any { mode ->
            if (mode.isEmpty()) sat.modes.isEmpty() else mode in sat.modes
        }
        val nameOk = query.isBlank() ||
            sat.name.contains(query, ignoreCase = true) ||
            sat.catalogNumber.toString().contains(query)
        val upcomingOk = !filter.onlyUpcoming || !sat.isCurrentlyVisible
        val inPassOk = !filter.onlyInPass || sat.isCurrentlyVisible
        val amsatOk = !filter.onlyAmsat || sat.status.isNotBlank()
        val favoriteOk = !filter.onlyFavorites || sat.catalogNumber in favorites
        modeOk && nameOk && upcomingOk && inPassOk && amsatOk && favoriteOk
    }
}

@Immutable
data class LocationUiState(
    val isLoading: Boolean = false,
    val result: LocationResult? = null,
    val error: String? = null,
    val lastLocationUpdateTime: Instant? = null,
    val lastLocationCity: String = ""
)

@Immutable
data class SatelliteUiState(
    val isSatelliteLoading: Boolean = false,
    val satellites: List<SatelliteInfo> = emptyList(),
    val satelliteError: String? = null,
    val lastSatelliteUpdateTime: Instant? = null,
    val cachedTles: List<SourcedTLE> = emptyList(),
    val segmentStatuses: Map<Int, List<SegmentStatus>> = emptyMap()
)