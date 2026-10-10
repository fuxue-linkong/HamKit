package com.example.hamkit.data.aprs

import android.content.Context
import android.content.SharedPreferences

class AprsSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var callsign: String
        get() = prefs.getString(KEY_CALLSIGN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CALLSIGN, value).apply() }

    var ssid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SSID, value).apply() }

    var passcode: String
        get() = prefs.getString(KEY_PASSCODE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PASSCODE, value).apply() }

    var server: String
        get() = prefs.getString(KEY_SERVER, "china.aprs2.net") ?: "china.aprs2.net"
        set(value) { prefs.edit().putString(KEY_SERVER, value).apply() }

    var port: Int
        get() = prefs.getInt(KEY_PORT, 14580)
        set(value) { prefs.edit().putInt(KEY_PORT, value).apply() }

    var useTls: Boolean
        get() = prefs.getBoolean(KEY_USE_TLS, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_TLS, value).apply() }

    var symbolTable: Char
        get() = (prefs.getInt(KEY_SYMBOL_TABLE, '/'.code)).toChar()
        set(value) { prefs.edit().putInt(KEY_SYMBOL_TABLE, value.code).apply() }

    var symbolCode: Char
        get() = (prefs.getInt(KEY_SYMBOL_CODE, '>'.code)).toChar()
        set(value) { prefs.edit().putInt(KEY_SYMBOL_CODE, value.code).apply() }

    var comment: String
        get() = prefs.getString(KEY_COMMENT, "HamKit") ?: "HamKit"
        set(value) { prefs.edit().putString(KEY_COMMENT, value).apply() }

    var transmitInterval: Int
        get() = prefs.getInt(KEY_INTERVAL, 60)
        set(value) { prefs.edit().putInt(KEY_INTERVAL, value).apply() }

    var useCompression: Boolean
        get() = prefs.getBoolean(KEY_COMPRESSION, true)
        set(value) { prefs.edit().putBoolean(KEY_COMPRESSION, value).apply() }

    var enableTransmit: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_TX, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLE_TX, value).apply() }

    var enableReceive: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_RX, true)
        set(value) { prefs.edit().putBoolean(KEY_ENABLE_RX, value).apply() }

    var filter: String
        get() = prefs.getString(KEY_FILTER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_FILTER, value).apply() }

    /**
     * 是否已确认「持照与责任声明」（HK-REQ-004）。
     *
     * 首次连接 APRS-IS 前必须确认；确认后持久化，不再重复弹出。
     */
    var licenseConfirmed: Boolean
        get() = prefs.getBoolean(KEY_LICENSE_CONFIRMED, false)
        set(value) { prefs.edit().putBoolean(KEY_LICENSE_CONFIRMED, value).apply() }

    fun toConfig(): AprsConfig = AprsConfig(
        callsign = callsign,
        ssid = ssid,
        passcode = passcode,
        server = server,
        port = port,
        useTls = useTls,
        symbolTable = symbolTable,
        symbolCode = symbolCode,
        comment = comment,
        transmitInterval = transmitInterval,
        useCompression = useCompression,
        enableTransmit = enableTransmit,
        enableReceive = enableReceive,
        filter = filter,
        licenseConfirmed = licenseConfirmed
    )

    fun fromConfig(config: AprsConfig) {
        callsign = config.callsign
        ssid = config.ssid
        passcode = config.passcode
        server = config.server
        port = config.port
        useTls = config.useTls
        symbolTable = config.symbolTable
        symbolCode = config.symbolCode
        comment = config.comment
        transmitInterval = config.transmitInterval
        useCompression = config.useCompression
        enableTransmit = config.enableTransmit
        enableReceive = config.enableReceive
        filter = config.filter
        licenseConfirmed = config.licenseConfirmed
    }

    companion object {
        private const val PREFS_NAME = "aprs_settings"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSCODE = "passcode"
        private const val KEY_SERVER = "server"
        private const val KEY_PORT = "port"
        private const val KEY_USE_TLS = "use_tls"
        private const val KEY_SYMBOL_TABLE = "symbol_table"
        private const val KEY_SYMBOL_CODE = "symbol_code"
        private const val KEY_COMMENT = "comment"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_COMPRESSION = "compression"
        private const val KEY_ENABLE_TX = "enable_tx"
        private const val KEY_ENABLE_RX = "enable_rx"
        private const val KEY_FILTER = "filter"
        private const val KEY_LICENSE_CONFIRMED = "license_confirmed"
    }
}
