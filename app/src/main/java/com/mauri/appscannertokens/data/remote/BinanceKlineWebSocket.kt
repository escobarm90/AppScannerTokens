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
import org.ta4j.core.BaseBar
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class BinanceKlineWebSocket(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var running = false

    fun start(
        symbols: List<String>,
        timeframe: String,
        duration: Duration,
        marketData: Map<String, TokenData>
    ) {
        if (running && socket != null) return
        running = true
        reconnectJob?.cancel()

        val streams = symbols.joinToString("/") { "${it.lowercase()}@kline_$timeframe" }
        val request = Request.Builder().url("wss://fstream.binance.com/stream?streams=$streams").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val data = JsonParser.parseString(text).asJsonObject.getAsJsonObject("data")
                    val kline = data.getAsJsonObject("k")
                    updateCandle(
                        symbol = data.get("s").asString,
                        open = kline.get("o").asDouble,
                        high = kline.get("h").asDouble,
                        low = kline.get("l").asDouble,
                        close = kline.get("c").asDouble,
                        volume = kline.get("v").asDouble,
                        closeTime = kline.get("T").asLong,
                        duration = duration,
                        marketData = marketData
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                scheduleReconnect(symbols, timeframe, duration, marketData)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                scheduleReconnect(symbols, timeframe, duration, marketData)
            }
        })
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        socket?.close(1000, "Kline detenido")
        socket = null
    }

    private fun scheduleReconnect(
        symbols: List<String>,
        timeframe: String,
        duration: Duration,
        marketData: Map<String, TokenData>
    ) {
        if (!running || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(5000)
            if (running) start(symbols, timeframe, duration, marketData)
        }
    }

    private fun updateCandle(
        symbol: String,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double,
        closeTime: Long,
        duration: Duration,
        marketData: Map<String, TokenData>
    ) {
        val tokenData = marketData[symbol] ?: return
        val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(closeTime), ZoneId.of("UTC"))

        synchronized(tokenData) {
            val bar = BaseBar(
                duration,
                time,
                tokenData.series.numOf(open),
                tokenData.series.numOf(high),
                tokenData.series.numOf(low),
                tokenData.series.numOf(close),
                tokenData.series.numOf(volume),
                tokenData.series.numOf(0.0)
            )

            if (tokenData.series.barCount == 0) return
            if (time.isEqual(tokenData.series.lastBar.endTime)) {
                tokenData.series.addBar(bar, true)
            } else if (time.isAfter(tokenData.series.lastBar.endTime)) {
                tokenData.series.addBar(bar)
            }
        }
    }
}
