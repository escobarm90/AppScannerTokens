package com.mauri.appscannertokens.data.remote

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BinanceTickerWebSocket(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prices = ConcurrentHashMap<String, Double>()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var running = false

    val snapshot: Map<String, Double>
        get() = prices

    fun price(symbol: String): Double? = prices[symbol]

    fun start() {
        if (running && socket != null) return
        running = true
        reconnectJob?.cancel()

        val request = Request.Builder().url("wss://fstream.binance.com/ws/!ticker@arr").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val arr = JsonParser.parseString(text).asJsonArray
                    for (i in 0 until arr.size()) {
                        val obj = arr[i].asJsonObject
                        prices[obj.get("s").asString] = obj.get("c").asDouble
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                scheduleReconnect()
            }
        })
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        socket?.close(1000, "Ticker detenido")
        socket = null
    }

    private fun scheduleReconnect() {
        if (!running || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(5000)
            if (running) start()
        }
    }
}
