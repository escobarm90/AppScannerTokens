package com.mauri.appscannertokens

import android.app.NotificationChannel
import android.app.NotificationManager
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
import java.math.BigDecimal
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
                        // Leemos seguro como String y convertimos para evitar crasheos de Gson
                        val quoteVol = it.get("quoteVolume").asString.toDoubleOrNull() ?: 0.0
                        // Piso de 3M asegura que lleguemos siempre a 50 tokens
                        s.endsWith("USDT") && !s.contains("_") && quoteVol > 15000000.0
                    }
                    .sortedByDescending { Math.abs(it.get("priceChangePercent").asString.toDoubleOrNull() ?: 0.0) }
                    .take(50) // Fuerza a tomar los 50 más volátiles (Ignorando stablecoins)
                    .map { it.get("symbol").asString }
            }
        } catch (e: Exception) {
            Log.e("ScannerDebug", "Error obteniendo pares: ${e.message}")
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

        synchronized(tData) {
            try {
                // El formato a prueba de fallos: BigDecimal.valueOf(x).toPlainString()
                // Evita la notación científica sin importar cuántos decimales tenga el token.
                val bar = BaseBar(d, time,
                    DecimalNum.valueOf(BigDecimal.valueOf(o).toPlainString()),
                    DecimalNum.valueOf(BigDecimal.valueOf(h).toPlainString()),
                    DecimalNum.valueOf(BigDecimal.valueOf(l).toPlainString()),
                    DecimalNum.valueOf(BigDecimal.valueOf(c).toPlainString()),
                    DecimalNum.valueOf(BigDecimal.valueOf(v).toPlainString()),
                    DecimalNum.valueOf("0.0")
                )
                if (tData.series.barCount == 0) return
                if (time.isEqual(tData.series.lastBar.endTime)) tData.series.addBar(bar, true)
                else if (time.isAfter(tData.series.lastBar.endTime)) tData.series.addBar(bar)
            } catch (e: Exception) { }
        }
    }

    // =========================================================================
    // LÓGICA DE ESTRATEGIA (CON TERMINAL EN VIVO RESTAURADA)
    // =========================================================================
    private fun evaluateStrategy(symbol: String, tData: TokenData) {
        val idx: Int; val prev: Int; val ante: Int
        val closeActual: Double; val closeCerrada: Double; val closePrevia: Double
        val rsiActual: Double; val adxActual: Double; val atrActual: Double
        val ema7Actual: Double; val ema7Previa: Double; val ema200Actual: Double
        val volCerrado: Double; val volSma: Double
        val macdHistAct: Double; val macdHistPrev: Double
        val bbUpperActual: Double; val bbLowerActual: Double

        // BLOQUEO: Tomamos la foto matemática de forma segura para evitar el NaN
        synchronized(tData) {
            idx = tData.series.endIndex
            prev = idx - 1
            ante = idx - 2

            if (ante < 0) return

            closeActual = tData.closePrice.getValue(idx).doubleValue()
            closeCerrada = tData.closePrice.getValue(prev).doubleValue()
            closePrevia = tData.closePrice.getValue(ante).doubleValue()

            rsiActual = tData.rsi.getValue(prev).doubleValue()
            adxActual = tData.adx.getValue(prev).doubleValue()
            atrActual = tData.atr.getValue(idx).doubleValue()
            ema7Actual = tData.ema7.getValue(prev).doubleValue()
            ema7Previa = tData.ema7.getValue(ante).doubleValue()
            ema200Actual = tData.ema200.getValue(prev).doubleValue()

            volCerrado = tData.volumeInd.getValue(prev).doubleValue()
            volSma = tData.volSma.getValue(prev).doubleValue()

            macdHistAct = tData.macdLine.getValue(prev).doubleValue() - tData.macdSignal.getValue(prev).doubleValue()
            macdHistPrev = tData.macdLine.getValue(ante).doubleValue() - tData.macdSignal.getValue(ante).doubleValue()
            bbUpperActual = tData.bbUpper.getValue(prev).doubleValue()
            bbLowerActual = tData.bbLower.getValue(prev).doubleValue()
        }

        val atrPct = if (closeActual > 0) (atrActual / closeActual * 100) else 0.0
        val spread = spreadCache[symbol] ?: 0.0
        val volRatio = if (volSma > 0) volCerrado / volSma else 0.0

        // =========================================================
        // RESTAURACIÓN DE TU PANEL DEBUG (Lo que borré por error)
        // =========================================================
        val log = """
            --- ANALIZANDO: $symbol ---
            Precio: $closeActual | RSI: ${String.format(Locale.US, "%.2f", rsiActual)}
            ATR (%): ${String.format(Locale.US, "%.3f", atrPct)}% (Req: >= 0.50%)
            ADX (14): ${String.format(Locale.US, "%.2f", adxActual)} (Req: >= 18)
            BB UPPER: ${String.format(Locale.US, "%.5f", bbUpperActual)} | BB LOWER: ${String.format(Locale.US, "%.5f", bbLowerActual)}
            Vol Ratio: ${String.format(Locale.US, "%.2f", volRatio)}x (Req: >= 1.20x)
            Spread: ${String.format(Locale.US, "%.3f", spread)}% (Req: <= 0.10%)
        """.trimIndent()

        emitirLogApp(log) // Vuelve a imprimir todos los datos en pantalla
        // =========================================================

        // RECHAZOS TÉCNICOS CON MOTIVO VISIBLE
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

        // FILTRO BOLLINGER ANTI-EXTENUACIÓN
        if (senal == "LONG" && closeCerrada >= bbUpperActual) {
            emitirLogApp("❌ RECHAZADO: EXTENUACIÓN ALCISTA (Rozando techo de Bollinger)")
            return
        }
        if (senal == "SHORT" && closeCerrada <= bbLowerActual) {
            emitirLogApp("❌ RECHAZADO: EXTENUACIÓN BAJISTA (Rozando suelo de Bollinger)")
            return
        }

        // VERIFICACIÓN DE MUROS Y TRADES
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Verificar Orderbook (Muros Limit)
                val obUrl = "https://fapi.binance.com/fapi/v1/depth?symbol=$symbol&limit=5"
                val obResp = client.newCall(Request.Builder().url(obUrl).build()).execute().body?.string() ?: return@launch
                if (!obResp.startsWith("{")) return@launch

                val ob = JsonParser.parseString(obResp).asJsonObject
                var bidQty = 0.0; var askQty = 0.0
                ob.getAsJsonArray("bids").forEach { bidQty += it.asJsonArray[1].asDouble }
                ob.getAsJsonArray("asks").forEach { askQty += it.asJsonArray[1].asDouble }

                if (senal == "LONG" && askQty > (bidQty * 3.5)) { emitirLogApp("❌ ESTADO: RECHAZADO - MURO DE VENTA"); return@launch }
                if (senal == "SHORT" && bidQty > (askQty * 3.5)) { emitirLogApp("❌ ESTADO: RECHAZADO - MURO DE COMPRA"); return@launch }

                // 2. Verificar Flujo de Trades Reales
                val trUrl = "https://fapi.binance.com/fapi/v1/trades?symbol=$symbol&limit=500"
                val trResp = client.newCall(Request.Builder().url(trUrl).build()).execute().body?.string() ?: return@launch
                if (!trResp.startsWith("[")) return@launch

                val trJson = JsonParser.parseString(trResp).asJsonArray
                var volTotal = 0.0; var volCompras = 0.0
                trJson.forEach {
                    val qty = it.asJsonObject.get("qty").asDouble
                    val isBuyerMaker = it.asJsonObject.get("isBuyerMaker").asBoolean
                    volTotal += qty
                    if (!isBuyerMaker) volCompras += qty
                }
                val pctCompras = if (volTotal > 0) (volCompras / volTotal) * 100 else 50.0

                if ((senal == "LONG" && pctCompras < 40.0) || (senal == "SHORT" && pctCompras > 60.0)) {
                    emitirLogApp("❌ ESTADO: RECHAZADO - FUERZA INSUFICIENTE EN TRADES (${String.format(Locale.US, "%.1f", pctCompras)}%)")
                    return@launch
                }

                // 3. Sistema de Cooldown
                val last = registroBloqueo[symbol] ?: 0L
                if (System.currentTimeMillis() - last < 300000) { emitirLogApp("⏳ ESTADO: IGNORADO - COOLDOWN"); return@launch }

                // 4. Cálculo exacto de SL y TP
                var distSl = atrActual * config.multiplicadorSl
                val topeMax = closeActual * 0.008
                val topeMin = closeActual * 0.003

                if (distSl > topeMax) distSl = topeMax
                if (distSl < topeMin) distSl = topeMin

                val distTp = distSl * config.multiplicadorTp
                val tp = if (senal == "LONG") closeActual + distTp else closeActual - distTp
                val sl = if (senal == "LONG") closeActual - distSl else closeActual + distSl

                // Aprobación Final
                registroBloqueo[symbol] = System.currentTimeMillis()
                emitirLogApp("🎯 ¡OPORTUNIDAD $senal DETECTADA EN $symbol!")

                val velasEst = Math.max(1, (distTp / atrActual).toInt())
                AlertManager.agregarAlerta(this@TradingScannerService, AlertData(symbol, senal, closeActual, tp, sl, velasEst, config.timeframe))

                AlertManager.agregarAlerta(
                    this@TradingScannerService,
                    AlertData(symbol, senal, closeActual, tp, sl, velasEst, config.timeframe)
                )

            } catch (e: Exception) {
                emitirLogApp("❌ Error API en validación final: ${e.message}")
            }
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

    data class AlertData(
        val symbol: String,
        val senal: String,
        val precio: Double,      // Cambiado para coincidir con la UI
        val tp: Double,          // Cambiado para coincidir con la UI
        val sl: Double,          // Cambiado para coincidir con la UI
        val velasEstimadas: Int,
        val timeframe: String,
        val timestamp: Long = System.currentTimeMillis() // Agregado para la hora
    )

    override fun onDestroy() { isRunning = false; webSocket?.close(1000, "Stop"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}