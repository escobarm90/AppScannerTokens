package com.mauri.appscannertokens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.adx.ADXIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.ATRIndicator
import org.ta4j.core.num.DoubleNum
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TradingScannerService : Service() {

    private val client = OkHttpClient()
    private val gson = Gson()
    private lateinit var config: UserConfig
    private var webSocket: WebSocket? = null

    private val marketData = mutableMapOf<String, BaseBarSeries>()

    companion object {
        const val CHANNEL_ID = "TradingScannerChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        config = cargarConfiguracion()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Escáner Activo")
            .setContentText("Buscando oportunidades en Binance (${config.timeframe})")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        Thread {
            iniciarMotorEscaner()
        }.start()

        return START_STICKY
    }

    private fun cargarConfiguracion(): UserConfig {
        val prefs = getSharedPreferences("AppScannerConfig", Context.MODE_PRIVATE)
        val jsonGuardado = prefs.getString("config_data", null)
        return if (jsonGuardado != null) {
            gson.fromJson(jsonGuardado, UserConfig::class.java)
        } else {
            UserConfig()
        }
    }

    private fun iniciarMotorEscaner() {
        Log.d("Scanner", "Iniciando motor Multiusuario...")

        val topSymbols = obtenerTopPares()
        val duration = parseTimeframe(config.timeframe)

        topSymbols.forEach { symbol ->
            val series = BaseBarSeriesBuilder().withName(symbol).build()
            series.maximumBarCount = 300
            descargarHistorial(symbol, config.timeframe, series, duration)
            marketData[symbol] = series
        }

        conectarWebSocket(topSymbols, duration)
    }

    private fun obtenerTopPares(): List<String> {
        val request = Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/24hr").build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                val tickers = JsonParser.parseString(body).asJsonArray
                tickers.map { it.asJsonObject }
                    .filter { it.get("symbol").asString.endsWith("USDT") }
                    .sortedByDescending { it.get("quoteVolume").asDouble }
                    .take(50)
                    .map { it.get("symbol").asString }
            }
        } catch (e: Exception) {
            Log.e("Scanner", "Error obteniendo top pares: ${e.message}")
            listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")
        }
    }

    private fun descargarHistorial(symbol: String, timeframe: String, series: BaseBarSeries, duration: Duration) {
        val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$timeframe&limit=250"
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return
                val klines = JsonParser.parseString(body).asJsonArray
                for (k in klines) {
                    val kline = k.asJsonArray
                    val endTime = Instant.ofEpochMilli(kline.get(6).asLong)
                    val bar = BaseBar(
                        duration,
                        endTime.minus(duration),
                        endTime,
                        DoubleNum.valueOf(kline.get(1).asDouble),
                        DoubleNum.valueOf(kline.get(2).asDouble),
                        DoubleNum.valueOf(kline.get(3).asDouble),
                        DoubleNum.valueOf(kline.get(4).asDouble),
                        DoubleNum.valueOf(kline.get(5).asDouble),
                        DoubleNum.valueOf(0.0), // amount
                        0L // trades
                    )
                    series.addBar(bar)
                }
            }
        } catch (e: Exception) {
            Log.e("Scanner", "Error descargando historial para $symbol: ${e.message}")
        }
    }

    private fun conectarWebSocket(symbols: List<String>, duration: Duration) {
        val timeframe = config.timeframe
        val streams = symbols.joinToString("/") { "${it.lowercase()}@kline_$timeframe" }
        val wsUrl = "wss://fstream.binance.com/stream?streams=$streams"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    if (!json.has("data")) return
                    val data = json.getAsJsonObject("data")
                    val kline = data.getAsJsonObject("k")
                    
                    val symbol = data.get("s").asString
                    val isKLineClosed = kline.get("x").asBoolean
                    val closePrice = kline.get("c").asDouble
                    val openPrice = kline.get("o").asDouble
                    val highPrice = kline.get("h").asDouble
                    val lowPrice = kline.get("l").asDouble
                    val volume = kline.get("v").asDouble
                    val closeTime = kline.get("T").asLong

                    actualizarVela(symbol, openPrice, highPrice, lowPrice, closePrice, volume, closeTime, isKLineClosed, duration)
                } catch (e: Exception) {
                    Log.e("Scanner", "Error parseando WS: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Scanner", "Error WebSocket: ${t.message}. Reiniciando en 10s...")
                Thread.sleep(10000)
                conectarWebSocket(symbols, duration)
            }
        })
    }

    private fun actualizarVela(
        symbol: String, open: Double, high: Double, low: Double, close: Double, 
        volume: Double, closeTime: Long, isClosed: Boolean, duration: Duration
    ) {
        val series = marketData[symbol] ?: return
        val endInstant = Instant.ofEpochMilli(closeTime)

        val bar = BaseBar(
            duration,
            endInstant.minus(duration),
            endInstant,
            DoubleNum.valueOf(open),
            DoubleNum.valueOf(high),
            DoubleNum.valueOf(low),
            DoubleNum.valueOf(close),
            DoubleNum.valueOf(volume),
            DoubleNum.valueOf(0.0), // amount
            0L // trades
        )

        try {
            if (series.barCount > 0 && series.lastBar.endTime.toInstant() == endInstant) {
                series.addBar(bar, true) // Reemplazar última si es el mismo tiempo
            } else if (isClosed) {
                series.addBar(bar)
                evaluateStrategy(symbol, series)
            }
        } catch (e: Exception) {
            Log.e("Scanner", "Error actualizando serie $symbol: ${e.message}")
        }
    }

    private fun evaluateStrategy(symbol: String, series: BaseBarSeries) {
        if (series.barCount < 200) return
        
        val closePriceInd = ClosePriceIndicator(series)
        val rsi = RSIIndicator(closePriceInd, 14)
        val ema7 = EMAIndicator(closePriceInd, 7)
        val ema200 = EMAIndicator(closePriceInd, 200)
        val adx = ADXIndicator(series, 14)
        val atr = ATRIndicator(series, 14)

        val lastIdx = series.endIndex
        val prevIdx = lastIdx - 1

        val closeActual = closePriceInd.getValue(lastIdx).doubleValue()
        val closePrevio = closePriceInd.getValue(prevIdx).doubleValue()
        val ema7Actual = ema7.getValue(lastIdx).doubleValue()
        val ema7Previo = ema7.getValue(prevIdx).doubleValue()
        val ema200Actual = ema200.getValue(lastIdx).doubleValue()
        val rsiActual = rsi.getValue(lastIdx).doubleValue()
        val adxActual = adx.getValue(lastIdx).doubleValue()
        val atrActual = atr.getValue(lastIdx).doubleValue()

        val atrPct = (atrActual / closeActual) * 100
        if (atrPct < config.minVolatilidadPct) return

        val tendenciaAlcista = closeActual > ema200Actual
        val tendenciaBajista = closeActual < ema200Actual
        val gatilloLong = (closePrevio <= ema7Previo) && (closeActual > ema7Actual)
        val gatilloShort = (closePrevio >= ema7Previo) && (closeActual < ema7Actual)

        var senal = "NEUTRAL"
        if (tendenciaAlcista && gatilloLong && adxActual >= 18.0 && rsiActual < 65.0) {
            senal = "LONG"
        } else if (tendenciaBajista && gatilloShort && adxActual >= 18.0 && rsiActual > 35.0) {
            senal = "SHORT"
        }

        if (senal != "NEUTRAL") {
            notificarAlerta(symbol, senal, closeActual, atrActual)
        }
    }

    private fun notificarAlerta(symbol: String, senal: String, precio: Double, atrValue: Double) {
        var distSl = atrValue * config.multiplicadorSl
        val topeMax = precio * 0.008
        val topeMin = precio * 0.003
        if (distSl > topeMax) distSl = topeMax
        if (distSl < topeMin) distSl = topeMin

        val distTp = distSl * config.multiplicadorTp
        val tp = if (senal == "LONG") precio + distTp else precio - distTp
        val sl = if (senal == "LONG") precio - distSl else precio + distSl

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎯 Señal: $symbol $senal")
            .setContentText("Entrada: $precio | TP: ${String.format(Locale.US, "%.4f", tp)} | SL: ${String.format(Locale.US, "%.4f", sl)}")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(symbol.hashCode(), notification)
    }

    private fun parseTimeframe(tf: String): Duration {
        return when (tf) {
            "1m" -> Duration.ofMinutes(1)
            "3m" -> Duration.ofMinutes(3)
            "5m" -> Duration.ofMinutes(5)
            "15m" -> Duration.ofMinutes(15)
            "1h" -> Duration.ofHours(1)
            else -> Duration.ofMinutes(5)
        }
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Motor Scanner Trading",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Servicio detenido")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
