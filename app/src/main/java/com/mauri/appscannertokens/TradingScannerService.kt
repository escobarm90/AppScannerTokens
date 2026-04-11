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

    val closePrice = ClosePriceIndicator(series)
    val volumeInd = VolumeIndicator(series)

    val rsi = RSIIndicator(closePrice, 14)
    val atr = ATRIndicator(series, 7)
    val ema7 = EMAIndicator(closePrice, 7)
    val ema200 = EMAIndicator(closePrice, 200)
    val volSma = SMAIndicator(volumeInd, 20)
    val adx = ADXIndicator(series, 14)

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

    private val marketData = ConcurrentHashMap<String, TokenData>()
    private val spreadCache = ConcurrentHashMap<String, Double>()
    private val registroBloqueo = ConcurrentHashMap<String, Long>()

    private var billeteraVirtualUsdt = 20.0

    // Control de velocidad del Logcat
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
            // Filtro a 50ms para que la terminal fluya en cascada sin crashear la UI
            if (ignorarLimiteDeTiempo || (now - lastLogTime > 50)) {
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

        topSymbols.forEach { marketData[it] = TokenData(it) }

        val executor = java.util.concurrent.Executors.newFixedThreadPool(10)

        topSymbols.forEach { symbol ->
            executor.submit {
                descargarHistorial(symbol, config.timeframe, duration)
            }
        }

        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.MINUTES)

        emitirLogApp("✅ Websockets Conectados. Analizando mercado en tiempo real...", true)
        conectarWebSocket(topSymbols, duration)

        launch { actualizarSpreads(topSymbols) }
    }

    private fun obtenerTopPares(): List<String> {
        val request = Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/24hr").build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                val tickers = JsonParser.parseString(body).asJsonArray

                val paresFiltrados = tickers.map { it.asJsonObject }
                    // 1. Filtrar solo pares USDT (sin barrera de volumen y omitiendo stables)
                    .filter {
                        val symbol = it.get("symbol").asString
                        symbol.endsWith("USDT") && symbol != "USDCUSDT" && symbol != "BUSDUSDT"
                    }
                    // 2. Ordenar por máxima volatilidad absoluta
                    .sortedByDescending { Math.abs(it.get("priceChangePercent").asDouble) }
                    // 3. Clavar el límite estricto en los 40 más explosivos
                    .take(40)
                    .map { it.get("symbol").asString }

                emitirLogApp("📊 ÉXITO: Se cargaron ${paresFiltrados.size} pares hiper-volátiles.", true)
                paresFiltrados
            }
        } catch (e: Exception) {
            verificarBaneo(e)
            val error = e.message ?: "Error de red"
            emitirLogApp("⚠️ Falló la API inicial ($error). Cargando 40 tokens de respaldo...", true)

            // Lista de emergencia si falla tu internet justo al arrancar
            listOf(
                "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "DOGEUSDT", "XRPUSDT", "ADAUSDT", "AVAXUSDT",
                "LINKUSDT", "DOTUSDT", "MATICUSDT", "LTCUSDT", "BCHUSDT", "APTUSDT", "ARBUSDT", "OPUSDT",
                "INJUSDT", "RNDRUSDT", "SUIUSDT", "SEIUSDT", "TIAUSDT", "GALAUSDT", "SANDUSDT", "MANAUSDT",
                "AXSUSDT", "SNXUSDT", "CRVUSDT", "LDOUSDT", "STXUSDT", "IMXUSDT", "NEARUSDT", "FILUSDT",
                "ATOMUSDT", "UNIUSDT", "AAVEUSDT", "MKRUSDT", "COMPUSDT", "DYDXUSDT", "WLDUSDT", "ORDIUSDT"
            )
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
            delay(5000)
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
                    emitirLogApp("⚠️ Error WS: ${t.message}. Reconectando...", true)
                    reconectarSeguro(symbols, duration)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isRunning) {
                    emitirLogApp("🔄 Binance cerró el WS (Normal). Reconectando...", true)
                    reconectarSeguro(symbols, duration)
                }
            }
        })
    }

    private fun reconectarSeguro(symbols: List<String>, duration: Duration) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(5000)
            conectarWebSocket(symbols, duration)
        }
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
            if (tokenData.series.barCount == 0) return

            val lastBar = tokenData.series.lastBar
            if (endZoned.isEqual(lastBar.endTime)) {
                // Actualiza el precio de la vela actual en vivo (sin esperar a que cierre)
                tokenData.series.addBar(bar, true)
                evaluateStrategy(symbol, tokenData)
            } else if (endZoned.isAfter(lastBar.endTime)) {
                // Pasa a la siguiente vela sin trabarse
                tokenData.series.addBar(bar)
                evaluateStrategy(symbol, tokenData)
            }
        } catch (e: Exception) { }
    }

    // =========================================================================
    // CORAZÓN MATEMÁTICO
    // =========================================================================
    private fun evaluateStrategy(symbol: String, tData: TokenData, esEvaluacionInicial: Boolean = false) {
        if (tData.series.barCount < 200) return

        val lastIdx = tData.series.endIndex
        val prevIdx = lastIdx - 1

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

        val macdHistAct = tData.macdLine.getValue(lastIdx).doubleValue() - tData.macdSignal.getValue(lastIdx).doubleValue()
        val macdHistPrev = tData.macdLine.getValue(prevIdx).doubleValue() - tData.macdSignal.getValue(prevIdx).doubleValue()

        if (esEvaluacionInicial) return

        val atrPct = if (closeActual > 0) (atrActual / closeActual) * 100 else 0.0
        val spread = spreadCache[symbol] ?: 0.0
        val volRatio = if (volSmaActual > 0) volActual / volSmaActual else 0.0

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

        // --- GATILLO ---
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

                val valPosNominal = margenOp * config.apalancamiento
                val cantMonedas = if (closeActual > 0) valPosNominal / closeActual else 0.0

                val perdidaPotencial = distSl * cantMonedas
                val riesgoMaximo = billeteraVirtualUsdt * 0.04

                // Validación de Riesgo Máximo (Resuelve el Warning de 'margenOp is never used')
                if (perdidaPotencial > riesgoMaximo && distSl > 0) {
                    margenOp = (riesgoMaximo / distSl) * (closeActual / config.apalancamiento)
                    Log.d("ScannerRiesgo", "Se redujo el margen a: $margenOp USDT para no superar el 4% de pérdida.")
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