package com.example.hamkit.ui.viewmodel

import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hamkit.data.aprs.AprsConfig
import com.example.hamkit.data.aprs.AprsConnection
import com.example.hamkit.data.aprs.AprsMessage
import com.example.hamkit.data.aprs.AprsMessageStore
import com.example.hamkit.data.aprs.AprsPacket
import com.example.hamkit.data.aprs.AprsPacketParser
import com.example.hamkit.data.aprs.AprsService
import com.example.hamkit.data.aprs.AprsSettingsStore
import com.example.hamkit.data.aprs.AprsStation
import com.example.hamkit.data.location.LocationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class AprsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = AprsSettingsStore(application)
    private val messageStore = AprsMessageStore(application)
    private val locationHelper = LocationHelper(application)

    /** 当前 APRS 配置（响应式 + 持久化到 SharedPreferences） */
    private val _settings = MutableStateFlow(settingsStore.toConfig())
    val settings = _settings.asStateFlow()

    private var connection: AprsConnection? = null
    private var connectionJob: Job? = null
    private var transmitJob: Job? = null
    private var msgCounter = AtomicInteger(1)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _stations = MutableStateFlow<List<AprsStation>>(emptyList())
    val stations: StateFlow<List<AprsStation>> = _stations.asStateFlow()

    private val _messages = MutableStateFlow<List<AprsMessage>>(emptyList())
    val messages: StateFlow<List<AprsMessage>> = _messages.asStateFlow()

    private val _conversationPartners = MutableStateFlow<List<String>>(emptyList())
    val conversationPartners: StateFlow<List<String>> = _conversationPartners.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastSentPacket = MutableStateFlow<String?>(null)
    val lastSentPacket: StateFlow<String?> = _lastSentPacket.asStateFlow()

    /**
     * 是否需要先弹出「持照与责任声明」确认（HK-REQ-004）。
     *
     * 用户点击「连接」但尚未确认声明时为 true；确认或取消后回到 false。
     */
    private val _pendingLicenseConfirm = MutableStateFlow(false)
    val pendingLicenseConfirm: StateFlow<Boolean> = _pendingLicenseConfirm.asStateFlow()

    /** 更新配置：内存 + 持久化，UI 通过 settings StateFlow 响应式刷新 */
    fun updateSettings(transform: (AprsConfig) -> AprsConfig) {
        val newConfig = transform(_settings.value)
        settingsStore.fromConfig(newConfig)
        _settings.value = newConfig
    }

    /**
     * 用户点击「连接」的入口。
     *
     * 首次连接（未确认持照声明）时不直接连接，而是发出确认请求，
     * 由 UI 弹出 [pendingLicenseConfirm] 对应的对话框；确认后走
     * [confirmLicenseAndConnect]，未确认不可登录（HK-REQ-004）。
     */
    fun requestConnect() {
        if (_settings.value.licenseConfirmed) {
            connect()
        } else {
            _pendingLicenseConfirm.value = true
        }
    }

    /** 用户在声明对话框中点击「已持有执照并同意」：持久化确认状态后再连接 */
    fun confirmLicenseAndConnect() {
        _pendingLicenseConfirm.value = false
        updateSettings { it.copy(licenseConfirmed = true) }
        connect()
    }

    /** 用户在声明对话框中取消：不连接、不落盘确认状态 */
    fun dismissLicenseConfirm() {
        _pendingLicenseConfirm.value = false
    }

    init {
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            messageStore.getRecentStations(STACTION_MAX_AGE_MS).collect {
                _stations.value = it
            }
        }
        viewModelScope.launch {
            messageStore.getAllMessages().collect {
                _messages.value = it
            }
        }
        viewModelScope.launch {
            messageStore.getConversationPartners().collect {
                _conversationPartners.value = it
            }
        }
        viewModelScope.launch {
            messageStore.getUnreadCount().collect {
                _unreadCount.value = it
            }
        }
    }

    fun connect() {
        val config = _settings.value
        if (config.callsign.isEmpty()) {
            _lastError.value = "请先设置呼号"
            return
        }
        // 合规兜底（HK-REQ-004）：未确认持照声明不得登录。
        // 正常路径由 requestConnect 弹窗确认，此处防御绕过 UI 的直接调用。
        if (!config.licenseConfirmed) {
            _pendingLicenseConfirm.value = true
            return
        }

        disconnect()

        _connectionState.value = ConnectionState.CONNECTING
        AprsService.start(getApplication())

        if (config.isReadOnly) {
            Log.i(
                TAG,
                "APRS-IS read-only session: no passcode supplied, using pass -1 " +
                    "(receive only, transmission rejected by server)"
            )
        }

        connectionJob = viewModelScope.launch {
            connection = AprsConnection(config, object : AprsConnection.AprsConnectionListener {
                override fun onConnected() {
                    _connectionState.value = ConnectionState.CONNECTED
                    sendPendingMessages()
                }

                override fun onDisconnected(reason: String) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }

                override fun onPacketReceived(packet: String) {
                    handlePacket(packet)
                }

                override fun onError(error: String) {
                    _connectionState.value = ConnectionState.ERROR
                    _lastError.value = error
                }
            })
            connection?.connect()
        }

        if (config.enableTransmit) {
            startTransmitLoop()
        }
    }

    fun disconnect() {
        transmitJob?.cancel()
        viewModelScope.launch {
            connection?.disconnect()
        }
        connection = null
        _connectionState.value = ConnectionState.DISCONNECTED
        AprsService.stop(getApplication())
    }

    fun transmitPosition(location: Location) {
        val config = _settings.value
        if (!config.enableTransmit) return
        // 只读模式（未填写 passcode）不可注入报文，否则只会得到服务器的拒绝
        if (config.isReadOnly) {
            _lastError.value = READ_ONLY_SEND_BLOCKED
            return
        }

        val packet = AprsPacket.formatPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            symbolTable = config.symbolTable,
            symbolCode = config.symbolCode,
            comment = config.comment,
            compressed = config.useCompression
        )

        val fullPacket = "${config.fullCallsign}>APRLAR:$packet"

        viewModelScope.launch {
            val sent = connection?.sendPacket(fullPacket) ?: false
            if (sent) {
                _lastSentPacket.value = fullPacket
                // 将本站位置存入站点表，地图优先显示本站
                messageStore.insertStation(
                    AprsStation(
                        callsign = config.fullCallsign,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        symbolTable = config.symbolTable,
                        symbolCode = config.symbolCode,
                        comment = config.comment,
                        altitude = if (location.hasAltitude()) location.altitude else null,
                        course = if (location.hasBearing()) location.bearing.toInt() else null,
                        speed = if (location.hasSpeed()) (location.speed * 1.94384449).toInt() else null
                    )
                )
            } else {
                _lastError.value = "位置上报失败：APRS-IS 未连接"
            }
        }
    }

    fun sendMessage(destination: String, message: String) {
        val config = _settings.value
        if (config.callsign.isEmpty()) {
            _lastError.value = "请先设置呼号"
            return
        }
        // 只读模式（未填写 passcode）不发送消息（HK-REQ-003）
        if (config.isReadOnly) {
            _lastError.value = READ_ONLY_SEND_BLOCKED
            return
        }

        val msgNum = msgCounter.getAndIncrement().toString()
        val fullCallsign = config.fullCallsign

        viewModelScope.launch {
            // 先写库（离线入队），再尝试发送
            val msgId = messageStore.insertMessage(
                AprsMessage(
                    source = fullCallsign,
                    destination = destination,
                    body = message,
                    isOutgoing = true,
                    msgNumber = msgNum,
                    status = MSG_STATUS_NEW
                )
            )

            if (_connectionState.value == ConnectionState.CONNECTED) {
                sendOutgoingMessage(msgId, fullCallsign, destination, message, msgNum, 0)
            } else {
                _lastError.value = "未连接，消息已暂存，连接后自动发送"
            }
        }
    }

    /** 发送单条 outgoing 消息并更新状态 */
    private suspend fun sendOutgoingMessage(
        msgId: Long, source: String, destination: String, body: String, msgNum: String, retry: Int
    ) {
        val packet = AprsPacket.formatMessage(source, destination, body, msgNum)
        val fullPacket = "$source>APRLAR:$packet"
        val sent = connection?.sendPacket(fullPacket) ?: false
        if (sent) {
            messageStore.updateMessageStatus(msgId, MSG_STATUS_SENT, retry)
            _lastSentPacket.value = fullPacket
        } else {
            messageStore.updateMessageStatus(msgId, MSG_STATUS_FAILED, retry)
        }
    }

    /** 连接成功后重发所有待发/失败消息（离线入队核心） */
    private fun sendPendingMessages() {
        val config = _settings.value
        if (config.callsign.isEmpty()) return
        // 只读模式不重发离线队列（HK-REQ-003）
        if (config.isReadOnly) return
        viewModelScope.launch {
            val pending = messageStore.getPendingOutgoingMessages()
            pending.forEach { msg ->
                if (msg.retryCount >= MSG_MAX_RETRY) return@forEach
                val msgNum = msg.msgNumber ?: msgCounter.getAndIncrement().toString()
                sendOutgoingMessage(
                    msg.id, config.fullCallsign, msg.destination, msg.body, msgNum, msg.retryCount + 1
                )
            }
        }
    }

    /** 收到消息后自动回复 ACK */
    private fun sendAck(destination: String, msgNumber: String) {
        val config = _settings.value
        if (config.callsign.isEmpty()) return
        // 只读模式无法发送 ACK（HK-REQ-003）
        if (config.isReadOnly) return
        val ackBody = "ack$msgNumber"
        val packet = AprsPacket.formatMessage(config.fullCallsign, destination, ackBody, null)
        val fullPacket = "${config.fullCallsign}>APRLAR:$packet"
        viewModelScope.launch {
            connection?.sendPacket(fullPacket)
        }
    }

    fun markConversationRead(callsign: String) {
        viewModelScope.launch {
            messageStore.markConversationRead(callsign)
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    fun clearOldData() {
        viewModelScope.launch {
            messageStore.deleteOldMessages(MESSAGE_MAX_AGE_MS)
            messageStore.deleteOldStations(STACTION_MAX_AGE_MS)
        }
    }

    private fun startTransmitLoop() {
        transmitJob?.cancel()
        transmitJob = viewModelScope.launch {
            // 等待 APRS-IS 连接完成，避免在未连接时上报
            while (_connectionState.value != ConnectionState.CONNECTED) {
                if (_connectionState.value == ConnectionState.ERROR ||
                    _connectionState.value == ConnectionState.DISCONNECTED
                ) return@launch
                delay(1000)
            }
            while (true) {
                try {
                    val location = locationHelper.getCurrentLocation()
                    transmitPosition(location)
                } catch (e: Exception) {
                    _lastError.value = "位置上报失败: ${e.message}"
                }
                delay(settingsStore.transmitInterval * 1000L)
            }
        }
    }

    private fun handlePacket(packet: String) {
        viewModelScope.launch {
            try {
                val parsed = AprsPacketParser.parse(packet)
                when (parsed) {
                    is AprsPacketParser.ParsedMessage -> {
                        if (parsed.isAck && parsed.msgNumber != null) {
                            // 收到 ACK，标记对应 outgoing 消息为已送达
                            messageStore.markAcknowledged(parsed.source, parsed.msgNumber)
                        } else if (!parsed.isAck && !parsed.isRej && parsed.msgNumber != null) {
                            // 普通消息：入库 + 自动回 ACK
                            messageStore.insertMessage(
                                AprsMessage(
                                    source = parsed.source,
                                    destination = parsed.destination,
                                    body = parsed.body,
                                    isOutgoing = false,
                                    msgNumber = parsed.msgNumber
                                )
                            )
                            sendAck(parsed.source, parsed.msgNumber)
                        } else if (!parsed.isAck && !parsed.isRej) {
                            // 无 msgNumber 的消息：仅入库
                            messageStore.insertMessage(
                                AprsMessage(
                                    source = parsed.source,
                                    destination = parsed.destination,
                                    body = parsed.body,
                                    isOutgoing = false,
                                    msgNumber = parsed.msgNumber
                                )
                            )
                        }
                    }
                    is AprsPacketParser.ParsedPosition -> {
                        messageStore.insertStation(
                            AprsStation(
                                callsign = parsed.source,
                                latitude = parsed.latitude,
                                longitude = parsed.longitude,
                                symbolTable = parsed.symbolTable,
                                symbolCode = parsed.symbolCode,
                                comment = parsed.comment,
                                altitude = parsed.altitude,
                                course = parsed.course,
                                speed = parsed.speed
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // 解析失败时忽略
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    companion object {
        private const val TAG = "AprsViewModel"
        private const val STACTION_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val MESSAGE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MSG_STATUS_NEW = 0
        const val MSG_STATUS_SENT = 1
        const val MSG_STATUS_ACKED = 2
        const val MSG_STATUS_FAILED = 3
        private const val MSG_MAX_RETRY = 3

        /** 只读模式（未填写 passcode）下的发送拦截提示（HK-REQ-003） */
        const val READ_ONLY_SEND_BLOCKED = "只读模式：未填写 Passcode，无法发送"
    }
}
