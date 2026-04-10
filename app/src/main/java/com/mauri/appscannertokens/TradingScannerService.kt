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
// ESTRUCTURA OPTIMIZADA: Guardamos los indicadores en memoria (Caché)
// =========================================================================
class TokenData(symbol: String) {
    val series: BaseBarSeries = BaseBarSeriesBuilder().withName(symbol).build().apply {
        maximumBarCount = 300
    }

    // Al instanciar los indicadores una sola vez, TA4J usa su caché interno
    // y evita recalcular toda la historia cada vez que llega un tick.
    val closePrice = ClosePriceIndicator(series)
    val volumeInd = VolumeIndicator(series)

    val rsi = RSIIndicator(closePrice, 14)
    val atr = ATRIndicator(series, 7)
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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private lateinit var config: UserConfig
    private var webSocket: WebSocket? = null

    // Usamos ConcurrentHashMap por seguridad en Multihilo
    private val marketData = ConcurrentHashMap<String, TokenData>()
    private val spreadCache = ConcurrentHashMap<String, Double>()
    private val registroBloqueo = ConcurrentHashMap<String, Long>()

    private var billeteraVirtualUsdt = 20.0

    // Control de velocidad del Logcat para no saturar la pantalla de Compose
    private var lastLogTime = System.currentTimeMillis()

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
            .setContentTitle("Escáner Activo")
            .setContentText("Buscando oportunidades en Binance (${config.timeframe})")
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
        val jsonGuardado = prefs.getString("config_data", null)
        return if (jsonGuardado != null) gson.fromJson(jsonGuardado, UserConfig::class.java) else UserConfig()
    }

    private fun emitirLogApp(mensaje: String, ignorarLimiteDeTiempo: Boolean = false) {
        if (isDebugMode) {
            val now = System.currentTimeMillis()
            // Evitamos enviar miles de logs a Compose por segundo, permitiendo máximo 3 por segundo
            if (ignorarLimiteDeTiempo || (now - lastLogTime > 300)) {
                Log.d("ScannerDebug", mensaje)
                AlertManager.agregarLog(mensaje)
                lastLogTime = now
            }
        }
    }

    private suspend fun iniciarMotorEscaner() = coroutineScope {
        emitirLogApp("🚀 Filtrando contratos Perpetuos USDT...", true)

        val topSymbols = obtenerTopPares()
        val duration = parseTimeframe(config.timeframe)

        emitirLogApp("⏳ Descargando historial en paralelo (10 Hilos)...", true)

        // Inicializamos los contenedores
        topSymbols.forEach { marketData[it] = TokenData(it) }

        // Pool fijo de hilos para no sobrecargar el procesador en la descarga
        val executor = java.util.concurrent.Executors.newFixedThreadPool(10)

        topSymbols.forEach { symbol ->
            executor.submit {
                descargarHistorial(symbol, config.timeframe, duration)
            }
        }

        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.MINUTES)

        emitirLogApp("✅ Websockets Conectados. Analizando mercado...", true)
        conectarWebSocket(topSymbols, duration)

        launch { actualizarSpreads(topSymbols) }
    }

    private fun obtenerTopPares(): List<String> {
        val request = Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/24hr").build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                val tickers = JsonParser.parseString(body).asJsonArray
                tickers.map { it.asJsonObject }
                    .filter { it.get("symbol").asString.endsWith("USDT") && it.get("quoteVolume").asDouble > 15000000.0 }
                    .sortedByDescending { Math.abs(it.get("priceChangePercent").asDouble) }
                    .take(50)
                    .map { it.get("symbol").asString }
            }
        } catch (e: Exception) {
            verificarBaneo(e)
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
                        val obj = t.asJsonObject
                        val symbol = obj.get("symbol").asString
                        if (symbols.contains(symbol)) {
                            val bid = obj.get("bidPrice").asDouble
                            val ask = obj.get("askPrice").asDouble
                            if (bid > 0) spreadCache[symbol] = ((ask - bid) / bid) * 100
                        }
                    }
                }
            } catch (e: Exception) { }
            delay(5000) // Se actualiza cada 5 seg
        }
    }

    private fun descargarHistorial(symbol: String, timeframe: String, duration: Duration) {
        val tokenData = marketData[symbol] ?: return
        val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$timeframe&limit=250"
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) { verificarBaneo(Exception(response.code.toString())); return }
                val body = response.body?.string() ?: return
                val klines = JsonParser.parseString(body).asJsonArray

                for (k in klines) {
                    val kline = k.asJsonArray
                    val endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(kline.get(6).asLong), ZoneId.of("UTC"))
                    val bar = BaseBar(
                        duration, endTime,
                        DecimalNum.valueOf(kline.get(1).asString), DecimalNum.valueOf(kline.get(2).asString),
                        DecimalNum.valueOf(kline.get(3).asString), DecimalNum.valueOf(kline.get(4).asString),
                        DecimalNum.valueOf(kline.get(5).asString), DecimalNum.valueOf("0.0")
                    )
                    tokenData.series.addBar(bar)
                }

                // Calentamiento Forzado
                if (tokenData.series.barCount >= 200) {
                    evaluateStrategy(symbol, tokenData, esEvaluacionInicial = true)
                }

                emitirLogApp("[OK] Descargadas 250 velas de $symbol", true)
            }
        } catch (e: Exception) { verificarBaneo(e) }
    }

    private fun conectarWebSocket(symbols: List<String>, duration: Duration) {
        val timeframe = config.timeframe
        val streams = symbols.joinToString("/") { "${it.lowercase()}@kline_$timeframe" }
        val wsUrl = "wss://fstream.binance.com/stream?streams=$streams"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isRunning) return
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
                } catch (e: Exception) { }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isRunning) {
                    emitirLogApp("⚠️ Reconectando WS en 5s...", true)
                    Thread.sleep(5000)
                    conectarWebSocket(symbols, duration)
                }
            }
        })
    }

    private fun actualizarVela(
        symbol: String, open: Double, high: Double, low: Double, close: Double,
        volume: Double, closeTime: Long, isClosed: Boolean, duration: Duration
    ) {
        val tokenData = marketData[symbol] ?: return
        val endZoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(closeTime), ZoneId.of("UTC"))

        val bar = BaseBar(
            duration, endZoned,
            DecimalNum.valueOf(open.toString()), DecimalNum.valueOf(high.toString()),
            DecimalNum.valueOf(low.toString()), DecimalNum.valueOf(close.toString()),
            DecimalNum.valueOf(volume.toString()), DecimalNum.valueOf("0.0")
        )

        try {
            if (tokenData.series.barCount > 0 && tokenData.series.lastBar.endTime.isEqual(endZoned)) {
                tokenData.series.addBar(bar, true)

                // EVALUACIÓN EN TIEMPO REAL (Vela viva)
                evaluateStrategy(symbol, tokenData)

            } else if (isClosed) {
                tokenData.series.addBar(bar)
                evaluateStrategy(symbol, tokenData)
            }
        } catch (e: Exception) { }
    }

    // =========================================================================
    // CORAZÓN MATEMÁTICO EXTREMADAMENTE OPTIMIZADO
    // =========================================================================
    private fun evaluateStrategy(symbol: String, tData: TokenData, esEvaluacionInicial: Boolean = false) {
        if (tData.series.barCount < 200) return

        val lastIdx = tData.series.endIndex
        val prevIdx = lastIdx - 1

        // 1. Extraemos los valores EXIGIENDO el cálculo de la librería
        // Al estar los indicadores creados una sola vez en TokenData,
        // TA4J usa la caché interna y devuelve el valor en milisegundos.
        val closeActual = tData.closePrice.getValue(lastIdx).doubleValue()
        val closePrevio = tData.closePrice.getValue(prevIdx).doubleValue()

        val ema7Actual = tData.ema7.getValue(lastIdx).doubleValue()
        val ema7Previo = tData.ema7.getValue(prevIdx).doubleValue()
        val ema200Actual = tData.ema200.getValue(lastIdx).doubleValue()

        val rsiRaw = tData.rsi.getValue(lastIdx).doubleValue()
        val rsiActual = if (rsiRaw.isNaN()) 0.0 else rsiRaw

        val atrRaw = tData.atr.getValue(lastIdx).doubleValue()
        val atrActual = if (atrRaw.isNaN()) 0.0 else atrRaw

        val adxRaw = tData.adx.getValue(lastIdx).doubleValue()
        val adxActual = if (adxRaw.isNaN()) 0.0 else adxRaw

        val volActual = tData.volumeInd.getValue(lastIdx).doubleValue()
        val volSmaActual = tData.volSma.getValue(lastIdx).doubleValue()

        val bbUpperActual = tData.bbUpper.getValue(lastIdx).doubleValue()
        val bbLowerActual = tData.bbLower.getValue(lastIdx).doubleValue()

        val macdHistAct = tData.macdLine.getValue(lastIdx).doubleValue() - tData.macdSignal.getValue(lastIdx).doubleValue()
        val macdHistPrev = tData.macdLine.getValue(prevIdx).doubleValue() - tData.macdSignal.getValue(prevIdx).doubleValue()

        if (esEvaluacionInicial) return

        val atrPct = if (closeActual > 0) (atrActual / closeActual) * 100 else 0.0
        val spread = spreadCache[symbol] ?: 0.0
        val volRatio = if (volSmaActual > 0) volActual / volSmaActual else 0.0

        val hora = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        val logHeader = """
            
            --- ANALIZANDO: $symbol ---
            Precio: ${String.format(Locale.US, "%.5f", closeActual)} | ATR: ${String.format(Locale.US, "%.2f", atrPct)}%
            RSI: ${String.format(Locale.US, "%.1f", rsiActual)} | ADX: ${String.format(Locale.US, "%.1f", adxActual)}
            VolRatio: ${String.format(Locale.US, "%.2f", volRatio)}x | Spread: ${String.format(Locale.US, "%.2f", spread)}%
        """.trimIndent()

        // --- FILTROS DE RECHAZO ---
        if (spread > config.maxSpreadPct) {
            emitirLogApp("$logHeader\n❌ RECHAZADO: SPREAD ALTO")
            return
        }
        if (atrPct < config.minVolatilidadPct) {
            emitirLogApp("$logHeader\n❌ RECHAZADO: MERCADO PLANO")
            return
        }
        if (volRatio < config.minRatioVol) {
            emitirLogApp("$logHeader\n❌ RECHAZADO: VOLUMEN BAJO")
            return
        }

        // --- GATILLO FRANCOTIRADOR ---
        val tendenciaAlcista = closeActual > ema200Actual
        val tendenciaBajista = closeActual < ema200Actual

        val gatilloLong = (closePrevio <= ema7Previo) && (closeActual > ema7Actual)
        val gatilloShort = (closePrevio >= ema7Previo) && (closeActual < ema7Actual)

        val adxValido = adxActual >= 18.0
        val macdGirandoAlcista = macdHistAct > macdHistPrev
        val macdGirandoBajista = macdHistAct < macdHistPrev

        var senal = "NEUTRAL"
        if (tendenciaAlcista && gatilloLong && adxValido && rsiActual < 65.0 && macdGirandoAlcista) {
            senal = "LONG"
        } else if (tendenciaBajista && gatilloShort && adxValido && rsiActual > 35.0 && macdGirandoBajista) {
            senal = "SHORT"
        }

        if (senal == "NEUTRAL") {
            emitirLogApp("$logHeader\n⏸️ RECHAZADO: Sin cruce o indicadores no alineados")
            return
        }

        // --- SISTEMA DE COOLDOWN ---
        val ultimoRegistro = registroBloqueo[symbol]
        if (ultimoRegistro != null) {
            val tiempoPasado = (System.currentTimeMillis() - ultimoRegistro) / 1000
            if (tiempoPasado < config.cooldownAlertaSegundos) {
                emitirLogApp("$logHeader\n⏳ IGNORADO: Oportunidad en cooldown")
                return
            }
        }

        // =====================================================================
        // ORDEN BOOK Y TRADES (Llamadas asíncronas seguras)
        // =====================================================================
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val obUrl = "https://fapi.binance.com/fapi/v1/depth?symbol=$symbol&limit=5"
                val obResp = client.newCall(Request.Builder().url(obUrl).build()).execute().body?.string()
                val obJson = JsonParser.parseString(obResp).asJsonObject

                var bidQty = 0.0; var askQty = 0.0
                obJson.getAsJsonArray("bids").forEach { bidQty += it.asJsonArray[1].asDouble }
                obJson.getAsJsonArray("asks").forEach { askQty += it.asJsonArray[1].asDouble }

                val trUrl = "https://fapi.binance.com/fapi/v1/trades?symbol=$symbol&limit=500"
                val trResp = client.newCall(Request.Builder().url(trUrl).build()).execute().body?.string()
                val trJson = JsonParser.parseString(trResp).asJsonArray

                var volTotal = 0.0; var volCompras = 0.0
                trJson.forEach {
                    val qty = it.asJsonObject.get("qty").asDouble
                    val isBuyerMaker = it.asJsonObject.get("isBuyerMaker").asBoolean
                    volTotal += qty
                    if (!isBuyerMaker) volCompras += qty
                }
                val pctCompras = if (volTotal > 0) (volCompras / volTotal) * 100 else 50.0

                if (senal == "LONG" && askQty > (bidQty * 3.5)) {
                    emitirLogApp("$logHeader\n❌ RECHAZADO: MURO DE VENTA PELIGROSO")
                    return@launch
                } else if (senal == "SHORT" && bidQty > (askQty * 3.5)) {
                    emitirLogApp("$logHeader\n❌ RECHAZADO: MURO DE COMPRA PELIGROSO")
                    return@launch
                }

                if ((senal == "LONG" && pctCompras < 40.0) || (senal == "SHORT" && pctCompras > 60.0)) {
                    emitirLogApp("$logHeader\n❌ RECHAZADO: FUERZA INSUFICIENTE EN TRADES")
                    return@launch
                }

                var margenOp = billeteraVirtualUsdt * (config.porcentajeInversion / 100.0)
                var distSl = atrActual * config.multiplicadorSl

                val topeMax = closeActual * 0.008
                val topeMin = closeActual * 0.003
                if (distSl > topeMax) distSl = topeMax
                if (distSl < topeMin) distSl = topeMin

                var valPosNominal = margenOp * config.apalancamiento
                val cantMonedas = if (closeActual > 0) valPosNominal / closeActual else 0.0

                val perdidaPotencial = distSl * cantMonedas
                val riesgoMaximo = billeteraVirtualUsdt * 0.04

                if (perdidaPotencial > riesgoMaximo && distSl > 0) {
                    margenOp = (riesgoMaximo / distSl) * (closeActual / config.apalancamiento)
                }

                val distTp = distSl * config.multiplicadorTp
                val tp = if (senal == "LONG") closeActual + distTp else closeActual - distTp
                val sl = if (senal == "LONG") closeActual - distSl else closeActual + distSl

                registroBloqueo[symbol] = System.currentTimeMillis()

                emitirLogApp("$logHeader\n✅ ¡OPORTUNIDAD ENVIADA A LA INTERFAZ!", true)

                val nuevaAlerta = AlertData(
                    symbol = symbol,
                    senal = senal,
                    precio = closeActual,
                    tp = tp,
                    sl = sl,
                    velasEst = Math.max(1, (distTp / atrActual).toInt()),
                    timeframe = config.timeframe
                )
                AlertManager.agregarAlerta(nuevaAlerta)

                val intent = Intent(this@TradingScannerService, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(this@TradingScannerService, 0, intent, PendingIntent.FLAG_IMMUTABLE)

                val notification = NotificationCompat.Builder(this@TradingScannerService, CHANNEL_ID)
                    .setContentTitle("🎯 Señal: $symbol $senal")
                    .setContentText("Entrada: $closeActual | TP: ${String.format(Locale.US, "%.4f", tp)} | SL: ${String.format(Locale.US, "%.4f", sl)}")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(symbol.hashCode(), notification)

            } catch (e: Exception) {
                emitirLogApp("$logHeader\n❌ Error API en validación: ${e.message}")
            }
        }
    }

    private fun verificarBaneo(e: Exception) {
        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("429") || msg.contains("418") || msg.contains("banned")) {
            emitirLogApp("⚠️ BANEO DE IP DETECTADO. Hibernando 5 min...", true)
            Thread.sleep(305000)
        }
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
            val channel = NotificationChannel(CHANNEL_ID, "Motor Scanner Trading", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        webSocket?.close(1000, "Servicio detenido")
        emitirLogApp("🛑 Motor detenido.", true)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}