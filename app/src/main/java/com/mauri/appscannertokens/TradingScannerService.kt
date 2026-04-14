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
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.adx.ADXIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.helpers.VolumeIndicator
import org.ta4j.core.indicators.ATRIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.num.DecimalNum
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

// =========================================================================
// INDICADORES TÉCNICOS (REPLICA DE indicadores.py)
// =========================================================================
class TokenData(symbol: String) {
    val series: BaseBarSeries = BaseBarSeriesBuilder().withName(symbol).build().apply {
        maximumBarCount = 300 // VELAS_A_ANALIZAR
    }

    val closePrice = ClosePriceIndicator(series)
    val volumeInd = VolumeIndicator(series)

    val rsi = RSIIndicator(closePrice, 14) // RSI_PERIODOS
    val atr = ATRIndicator(series, 7) // ATR de 7 para reacción rápida
    val ema7 = EMAIndicator(closePrice, 7)
    val ema200 = EMAIndicator(closePrice, 200)
    val volSma = SMAIndicator(volumeInd, 20)
    val adx = ADXIndicator(series, 14)

    val stdDev = StandardDeviationIndicator(closePrice, 20)
    val sma20 = SMAIndicator(closePrice, 20)
    val bbMiddle = BollingerBandsMiddleIndicator(sma20)
    val bbUpper = BollingerBandsUpperIndicator(bbMiddle, stdDev, DecimalNum.valueOf("2.0"))
    val bbLower = BollingerBandsLowerIndicator(bbMiddle, stdDev, DecimalNum.valueOf("2.0"))

    val macdLine = MACDIndicator(closePrice, 12, 26)
    val macdSignal = EMAIndicator(macdLine, 9)
}

class TradingScannerService : Service() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private lateinit var config: UserConfig
    private var webSocket: WebSocket? = null

    private val marketData = ConcurrentHashMap<String, TokenData>()
    private val spreadCache = ConcurrentHashMap<String, Double>()
    private val registroBloqueo = ConcurrentHashMap<String, Long>()

    // 👇 ¡ESTA ES LA LÍNEA QUE FALTABA! 👇
    private var billeteraVirtualUsdt = 20.0

    companion object {
        const val CHANNEL_ID = "TradingScannerChannel"
        const val NOTIFICATION_ID = 1
        var isRunning = false
        var isDebugMode = true
    }

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        config = cargarConfiguracion()
        isRunning = true

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CMD SCANNER MOVIL")
            .setContentText("Escaneando mercado en tiempo real...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        CoroutineScope(Dispatchers.IO).launch {
            iniciarMotorEscaner()
        }

        return START_STICKY
    }

    private fun cargarConfiguracion(): UserConfig {
        val prefs = getSharedPreferences("AppScannerConfig", Context.MODE_PRIVATE)
        val json = prefs.getString("config_data", null)
        return if (json != null) gson.fromJson(json, UserConfig::class.java) else UserConfig()
    }

    private fun emitirLogApp(mensaje: String) {
        Log.d("ScannerDebug", mensaje)
        AlertManager.agregarLog(mensaje)
    }

    private suspend fun actualizarBilletera() {
        while (isRunning) {
            try {
                if (config.apiKey.isNotEmpty() && config.apiSecret.isNotEmpty()) {
                    val saldoReal = BinanceApiManager.obtenerSaldoUSDT(config.apiKey, config.apiSecret)
                    if (saldoReal > 0) {
                        billeteraVirtualUsdt = saldoReal
                    }
                }
            } catch (e: Exception) {
                Log.e("ScannerDebug", "Error actualizando billetera: ${e.message}")
            }
            // Actualiza la billetera cada 1 minuto para no saturar la API
            delay(60000)
        }
    }

    private suspend fun iniciarMotorEscaner() = coroutineScope {
        emitirLogApp("🚀 Filtrando contratos Perpetuos USDT...")

        val topSymbols = obtenerTopPares()
        val duration = parseTimeframe(config.timeframe)

        emitirLogApp("⏳ Descargando historial de ${topSymbols.size} tokens...")

        topSymbols.forEach { marketData[it] = TokenData(it) }

        val executor = java.util.concurrent.Executors.newFixedThreadPool(5)
        topSymbols.forEach { symbol ->
            executor.submit { descargarHistorial(symbol, config.timeframe, duration) }
        }
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.MINUTES)

        emitirLogApp("✅ Iniciando Websockets y Bucle Secuencial...")
        conectarWebSocket(topSymbols, duration)

        launch { actualizarSpreads(topSymbols) }
        launch { actualizarBilletera() } // <--- AGREGAR ESTO

        // BUCLE SECUENCIAL EXACTO AL DE PYTHON
        launch {
            while (isRunning) {
                emitirLogApp("\n=== INICIANDO ESCANEO | HORA: ${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())} ===\n")

                for (symbol in topSymbols) {
                    if (!isRunning) break
                    val tokenData = marketData[symbol]
                    if (tokenData != null && tokenData.series.barCount >= 200) {
                        evaluateStrategy(symbol, tokenData)
                    }
                    delay(100) // DELAY_ENTRE_TOKENS = 0.1
                }

                delay(2000) // PAUSA_ENTRE_CICLOS = 2
            }
        }
    }

    private fun obtenerTopPares(): List<String> {
        val request = Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/24hr").build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                val tickers = JsonParser.parseString(body).asJsonArray
                tickers.map { it.asJsonObject }
                    .filter {
                        val s = it.get("symbol").asString
                        // VOLUMEN_MIN_24H = 15,000,000
                        s.endsWith("USDT") && it.get("quoteVolume").asDouble > 15000000.0
                    }
                    .sortedByDescending { Math.abs(it.get("priceChangePercent").asDouble) }
                    .take(50) // TOP_VOLATILES = 50
                    .map { it.get("symbol").asString }
            }
        } catch (e: Exception) {
            listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")
        }
    }

    private suspend fun actualizarSpreads(symbols: List<String>) {
        while (isRunning) {
            try {
                val request = Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/bookTicker").build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use
                    val tickers = JsonParser.parseString(body).asJsonArray
                    for (t in tickers) {
                        val symbol = t.asJsonObject.get("symbol").asString
                        if (symbols.contains(symbol)) {
                            val bid = t.asJsonObject.get("bidPrice").asDouble
                            val ask = t.asJsonObject.get("askPrice").asDouble
                            if (bid > 0) spreadCache[symbol] = ((ask - bid) / bid) * 100
                        }
                    }
                }
            } catch (e: Exception) { }
            delay(5000)
        }
    }

    private fun descargarHistorial(symbol: String, timeframe: String, duration: Duration) {
        val tokenData = marketData[symbol] ?: return
        // VELAS_A_ANALIZAR = 300
        val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$timeframe&limit=300"
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val klines = JsonParser.parseString(response.body?.string()).asJsonArray
                for (k in klines) {
                    val kline = k.asJsonArray
                    val endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(kline.get(6).asLong), ZoneId.of("UTC"))
                    val bar = BaseBar(duration, endTime,
                        DecimalNum.valueOf(kline.get(1).asString), DecimalNum.valueOf(kline.get(2).asString),
                        DecimalNum.valueOf(kline.get(3).asString), DecimalNum.valueOf(kline.get(4).asString),
                        DecimalNum.valueOf(kline.get(5).asString), DecimalNum.valueOf("0.0")
                    )
                    tokenData.series.addBar(bar)
                }
            }
        } catch (e: Exception) { }
    }

    private fun conectarWebSocket(symbols: List<String>, duration: Duration) {
        val streams = symbols.joinToString("/") { "${it.lowercase()}@kline_${config.timeframe}" }
        val wsUrl = "wss://fstream.binance.com/stream?streams=$streams"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JsonParser.parseString(text).asJsonObject.getAsJsonObject("data")
                    val k = data.getAsJsonObject("k")
                    actualizarVela(data.get("s").asString, k.get("o").asDouble, k.get("h").asDouble, k.get("l").asDouble, k.get("c").asDouble, k.get("v").asDouble, k.get("T").asLong, duration)
                } catch (e: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconectarSeguro(symbols, duration)
        })
    }

    private fun reconectarSeguro(symbols: List<String>, duration: Duration) {
        if (isRunning) CoroutineScope(Dispatchers.IO).launch { delay(5000); conectarWebSocket(symbols, duration) }
    }

    private fun actualizarVela(symbol: String, o: Double, h: Double, l: Double, c: Double, v: Double, t: Long, d: Duration) {
        val tData = marketData[symbol] ?: return
        val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.of("UTC"))
        val bar = BaseBar(d, time, DecimalNum.valueOf(o.toString()), DecimalNum.valueOf(h.toString()), DecimalNum.valueOf(l.toString()), DecimalNum.valueOf(c.toString()), DecimalNum.valueOf(v.toString()), DecimalNum.valueOf("0.0"))
        try {
            if (tData.series.barCount == 0) return
            if (time.isEqual(tData.series.lastBar.endTime)) tData.series.addBar(bar, true)
            else if (time.isAfter(tData.series.lastBar.endTime)) tData.series.addBar(bar)
        } catch (e: Exception) { }
    }

    // =========================================================================
    // LÓGICA DE ESTRATEGIA (REPLICA DE main.py)
    // =========================================================================
    private fun evaluateStrategy(symbol: String, tData: TokenData) {
        val idx = tData.series.endIndex
        val prev = idx - 1
        val ante = idx - 2

        val closeActual = tData.closePrice.getValue(idx).doubleValue()
        val closeCerrada = tData.closePrice.getValue(prev).doubleValue()
        val closePrevia = tData.closePrice.getValue(ante).doubleValue()

        val rsiActual = tData.rsi.getValue(prev).doubleValue()
        val adxActual = tData.adx.getValue(prev).doubleValue()
        val atrActual = tData.atr.getValue(idx).doubleValue()
        val ema7Actual = tData.ema7.getValue(prev).doubleValue()
        val ema7Previa = tData.ema7.getValue(ante).doubleValue()
        val ema200Actual = tData.ema200.getValue(prev).doubleValue()

        val volCerrado = tData.volumeInd.getValue(prev).doubleValue()
        val volSma = tData.volSma.getValue(prev).doubleValue()

        val macdHistAct = tData.macdLine.getValue(prev).doubleValue() - tData.macdSignal.getValue(prev).doubleValue()
        val macdHistPrev = tData.macdLine.getValue(ante).doubleValue() - tData.macdSignal.getValue(ante).doubleValue()

        val atrPct = if (closeActual > 0) (atrActual / closeActual * 100) else 0.0
        val spread = spreadCache[symbol] ?: 0.0
        val volRatio = if (volSma > 0) volCerrado / volSma else 0.0

        // PANEL MODO DEBUG EN CONSOLA
        val log = """
            --- ANALIZANDO: $symbol ---
            Precio: $closeActual | RSI: ${String.format("%.2f", rsiActual)}
            ATR (%): ${String.format("%.3f", atrPct)}% (Req: >= 0.50%)
            ADX (14): ${String.format("%.2f", adxActual)} (Req: >= 18)
            Vol Ratio: ${String.format("%.2f", volRatio)}x (Req: >= 1.20x)
            Spread: ${String.format("%.3f", spread)}% (Req: <= 0.10%)
        """.trimIndent()
        emitirLogApp(log)

        // RECHAZOS TÉCNICOS
        if (spread > 0.10) { emitirLogApp("ESTADO: RECHAZADO - SPREAD ALTO"); return }
        if (atrPct < 0.50) { emitirLogApp("ESTADO: RECHAZADO - MERCADO PLANO"); return }
        if (volRatio < 1.20) { emitirLogApp("ESTADO: RECHAZADO - VOLUMEN BAJO"); return }

        // GATILLO FRANCOTIRADOR
        val tendenciaAlcista = closeCerrada > ema200Actual
        val tendenciaBajista = closeCerrada < ema200Actual

        val gatilloLong = (closePrevia <= ema7Previa) && (closeCerrada > ema7Actual)
        val gatilloShort = (closePrevia >= ema7Previa) && (closeCerrada < ema7Actual)

        var senal = "NEUTRAL"
        if (tendenciaAlcista && gatilloLong && adxActual >= 18 && rsiActual < 65 && macdHistAct > macdHistPrev) {
            senal = "LONG"
        } else if (tendenciaBajista && gatilloShort && adxActual >= 18 && rsiActual > 35 && macdHistAct < macdHistPrev) {
            senal = "SHORT"
        }

        if (senal == "NEUTRAL") { emitirLogApp("ESTADO: SIN SEÑAL TÉCNICA"); return }

        // VERIFICACIÓN DE MUROS Y TRADES
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Orderbook check (3.5x ratio)
                val obUrl = "https://fapi.binance.com/fapi/v1/depth?symbol=$symbol&limit=5"
                val ob = JsonParser.parseString(client.newCall(Request.Builder().url(obUrl).build()).execute().body?.string()).asJsonObject
                var bidQty = 0.0; var askQty = 0.0
                ob.getAsJsonArray("bids").forEach { bidQty += it.asJsonArray[1].asDouble }
                ob.getAsJsonArray("asks").forEach { askQty += it.asJsonArray[1].asDouble }

                if (senal == "LONG" && askQty > (bidQty * 3.5)) { emitirLogApp("ESTADO: RECHAZADO - MURO DE VENTA"); return@launch }
                if (senal == "SHORT" && bidQty > (askQty * 3.5)) { emitirLogApp("ESTADO: RECHAZADO - MURO DE COMPRA"); return@launch }

                // Cooldown check (300s)
                val last = registroBloqueo[symbol] ?: 0L
                if (System.currentTimeMillis() - last < 300000) { emitirLogApp("ESTADO: IGNORADO - COOLDOWN"); return@launch }

                registroBloqueo[symbol] = System.currentTimeMillis()
                emitirLogApp("🎯 ¡OPORTUNIDAD $senal DETECTADA!")

                // Cálculo de TP/SL
                val distSl = atrActual * 1.5 // multiplicador_sl default
                val tp = if (senal == "LONG") closeActual + (distSl * 1.5) else closeActual - (distSl * 1.5)
                val sl = if (senal == "LONG") closeActual - distSl else closeActual + distSl

                AlertManager.agregarAlerta(AlertData(symbol, senal, closeActual, tp, sl, 1, config.timeframe))
            } catch (e: Exception) { }
        }
    }

    private fun parseTimeframe(tf: String): Duration = when (tf) {
        "1m" -> Duration.ofMinutes(1); "3m" -> Duration.ofMinutes(3)
        "5m" -> Duration.ofMinutes(5); "15m" -> Duration.ofMinutes(15)
        else -> Duration.ofMinutes(5)
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Motor Scanner", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() { isRunning = false; webSocket?.close(1000, "Stop"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}