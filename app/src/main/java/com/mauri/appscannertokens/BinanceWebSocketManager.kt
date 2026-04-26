package com.mauri.appscannertokens

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class BinanceWebSocketManager(
    private val config: UserConfig,
    private val marketData: Map<String, TokenData>,
    private val onLog: (String) -> Unit
) {
    // 1. OPTIMIZACIÓN: Se agregó pingInterval para mantener la conexión viva
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // <-- CRÍTICO: Evita desconexiones silenciosas
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun conectar(symbols: List<String>, duration: Duration) {
        // 2. OPTIMIZACIÓN: Limpiar cualquier socket previo flotante antes de reconectar
        webSocket?.cancel()

        val streams = symbols.joinToString("/") { "${it.lowercase()}@kline_${config.timeframe}" }
        val wsUrl = "wss://fstream.binance.com/stream?streams=$streams"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JsonParser.parseString(text).asJsonObject.getAsJsonObject("data")
                    val k = data.getAsJsonObject("k")
                    actualizarVela(
                        data.get("s").asString,
                        k.get("o").asDouble,
                        k.get("h").asDouble,
                        k.get("l").asDouble,
                        k.get("c").asDouble,
                        k.get("v").asDouble,
                        k.get("T").asLong,
                        duration
                    )
                } catch (e: Exception) {
                    Log.e("WSManager", "Error parseando mensaje: ${e.message}")
                }
            }

            // 3. OPTIMIZACIÓN: Manejar el cierre de 24hs forzado de Binance
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onLog("🔄 WebSocket cerrado por el exchange (Regla 24h). Reconectando...")
                reconectar(symbols, duration)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog("⚠️ WebSocket caído: ${t.message}. Reconectando en 5s...")
                reconectar(symbols, duration)
            }
        })
    }

    private fun reconectar(symbols: List<String>, duration: Duration) {
        scope.launch {
            delay(5000)
            conectar(symbols, duration)
        }
    }

    private fun actualizarVela(symbol: String, o: Double, h: Double, l: Double, c: Double, v: Double, t: Long, d: Duration) {
        val tData = marketData[symbol] ?: return
        val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.of("UTC"))

        synchronized(tData) {
            try {
                val bar = org.ta4j.core.BaseBar(d, time,
                    tData.series.numOf(o), tData.series.numOf(h),
                    tData.series.numOf(l), tData.series.numOf(c),
                    tData.series.numOf(v), tData.series.numOf(0.0)
                )
                if (tData.series.barCount == 0) return
                if (time.isEqual(tData.series.lastBar.endTime)) tData.series.addBar(bar, true)
                else if (time.isAfter(tData.series.lastBar.endTime)) tData.series.addBar(bar)
            } catch (e: Exception) { }
        }
    }

    fun cerrar() {
        webSocket?.close(1000, "Servicio detenido")
        scope.cancel()
    }
}