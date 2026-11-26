package com.bitbeat.ecg

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ConnectionState {
    STOPPED,
    WAITING_FOR_WIFI,
    CONNECTING,
    CONNECTED,
    NO_DATA,
    RECONNECTING
}

/**
 * WebSocket client for the ESP32.
 * - Sends all traffic through the Wi-Fi network, even without internet,
 *   so Android does not use mobile data instead.
 * - Reconnects automatically if the connection drops or samples stop.
 * - onSample and onSessionStart are called from a background thread.
 */
class EcgWebSocketClient(
    context: Context,
    private val url: String,
    private val onSample: (EcgSample) -> Unit,
    private val onSessionStart: () -> Unit
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ConnectionState.STOPPED)
    val state: StateFlow<ConnectionState> = _state

    @Volatile private var running = false
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var lastSampleAt = 0L
    @Volatile var invalidMessages = 0L
        private set

    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetwork = network
            if (running) connect()
        }

        override fun onLost(network: Network) {
            if (network == wifiNetwork) {
                wifiNetwork = null
                closeSocket()
                if (running) _state.value = ConnectionState.WAITING_FOR_WIFI
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        _state.value = ConnectionState.WAITING_FOR_WIFI
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // the ESP32 network has no internet
            .build()
        cm.requestNetwork(request, networkCallback)
        startWatchdog()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        closeSocket()
        try { cm.unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
        wifiNetwork = null
        _state.value = ConnectionState.STOPPED
    }

    private fun connect() {
        val network = wifiNetwork
        if (network == null) {
            _state.value = ConnectionState.WAITING_FOR_WIFI
            return
        }
        closeSocket()
        _state.value = ConnectionState.CONNECTING
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)   // use ONLY the Wi-Fi network
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            if (ws !== webSocket) return
            lastSampleAt = System.currentTimeMillis()
            onSessionStart()   // new session: forget the previous state
            _state.value = ConnectionState.CONNECTED
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (ws !== webSocket) return
            val sample = EcgParser.parse(text)
            if (sample == null) {
                invalidMessages++
                return
            }
            lastSampleAt = System.currentTimeMillis()
            if (_state.value != ConnectionState.CONNECTED) _state.value = ConnectionState.CONNECTED
            onSample(sample)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) = scheduleReconnect(ws)

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = scheduleReconnect(ws)
    }

    private fun scheduleReconnect(ws: WebSocket) {
        if (ws !== webSocket || !running) return
        webSocket = null
        _state.value = ConnectionState.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2000)
            if (running) connect()
        }
    }

    /** If samples stop: show a warning after 1.5 s and force a reconnection after 5 s. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(500)
                val st = _state.value
                if (st == ConnectionState.CONNECTED || st == ConnectionState.NO_DATA) {
                    val silence = System.currentTimeMillis() - lastSampleAt
                    if (silence > 5000) webSocket?.cancel()   // causes onFailure -> reconnect
                    else if (silence > 1500) _state.value = ConnectionState.NO_DATA
                }
            }
        }
    }

    private fun closeSocket() {
        val ws = webSocket
        webSocket = null   // so messages from the old socket are ignored
        ws?.cancel()
    }
}