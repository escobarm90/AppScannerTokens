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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.*
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseBarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.adx.ADXIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.ATRIndicator
import org.ta4j.core.num.DecimalNum
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

class TradingScannerService : Service() {

    // Cliente HTTP con tiempos de espera ampliados para evitar bloqueos
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private lateinit var config: UserConfig
    private var webSocket: WebSocket? = null

    // Mapa para almacenar el historial de velas en la memoria RAM
    private val marketData = mutableMapOf<String, BaseBarSeries>()

    companion object {
        const val CHANNEL_ID = "TradingScannerChannel"
        const val NOTIFICATION_ID = 1

        // Variables globales controladas por los switches en la MainActivity
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

        // Mantener el servicio vivo en segundo plano (Foreground Service)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Escáner Activo")
            .setContentText("Buscando oportunidades en Binance (${config.timeframe})")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        // Arrancamos el motor en un hilo secundario de Corrutinas
        CoroutineScope(Dispatchers.IO).launch {
            iniciarMotorEscaner()
        }

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

    // Función auxiliar para enviar textos a la consola de la app
    private fun emitirLogApp(mensaje: String) {
        if (isDebugMode) {
            AlertManager.agregarLog(mensaje)
        }
    }

    private suspend fun iniciarMotorEscaner() = coroutineScope {
        emitirLogApp("🚀 Iniciando motor Multiusuario...")
        Log.d("Scanner", "Iniciando motor Multiusuario...")

        val topSymbols = obtenerTopPares()
        val duration = parseTimeframe(config.timeframe)

        emitirLogApp("📡 Descargando historial para ${topSymbols.size} monedas...")
        Log.d("Scanner", "Descargando historial usando Corrutinas...")

        // Descarga Multihilo Paralela: Máxima velocidad
        val descargas = topSymbols.map { symbol ->
            async(Dispatchers.IO) {
                val series = BaseBarSeriesBuilder().withName(symbol).build()
                series.maximumBarCount = 300
                descargarHistorial(symbol, config.timeframe, series, duration)

                // Prevenir choques de memoria entre los diferentes hilos
                synchronized(marketData) {
                    marketData[symbol] = series
                }
            }
        }

        // Esperamos a que terminen las 50 descargas
        descargas.awaitAll()

        emitirLogApp("✅ ¡Historial descargado! Conectando a WebSockets de Binance...")
        Log.d("Scanner", "¡Historial descargado con éxito! Conectando a WebSockets...")
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
                    .take(50) // Analizamos el TOP 50 con más volumen
                    .map { it.get("symbol").asString }
            }
        } catch (e: Exception) {
            emitirLogApp("❌ Error obteniendo top pares: ${e.message}")
            Log.e("Scanner", "Error obteniendo top pares: ${e.message}")
            listOf("BTCUSDT", "ETHUSDT", "SOLUSDT") // Respaldo en caso de error
        }
    }

    private fun descargarHistorial(symbol: String, timeframe: String, series: BaseBarSeries, duration: Duration) {
        val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$timeframe&limit=250"
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("Scanner", "Error HTTP ${response.code} para $symbol")
                    return
                }
                val body = response.body?.string() ?: return
                val klines = JsonParser.parseString(body).asJsonArray
                for (k in klines) {
                    val kline = k.asJsonArray
                    val endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(kline.get(6).asLong), ZoneId.of("UTC"))

                    // Precisión extrema exigida por TA4J (DecimalNum)
                    val bar = BaseBar(
                        duration,
                        endTime,
                        DecimalNum.valueOf(kline.get(1).asString),
                        DecimalNum.valueOf(kline.get(2).asString),
                        DecimalNum.valueOf(kline.get(3).asString),
                        DecimalNum.valueOf(kline.get(4).asString),
                        DecimalNum.valueOf(kline.get(5).asString),
                        DecimalNum.valueOf("0.0")
                    )
                    series.addBar(bar)
                }
                // Aviso visual a la consola CMD de la app
                emitirLogApp("[OK] Descargadas 250 velas de $symbol")
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
                if (!isRunning) return // Si el motor se apagó, ignoramos los mensajes entrantes

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
                if (isRunning) {
                    emitirLogApp("⚠️ Desconexión de Binance. Reconectando en 10s...")
                    Log.e("Scanner", "Error WebSocket: ${t.message}. Reconectando en 10s...")
                    Thread.sleep(10000)
                    conectarWebSocket(symbols, duration)
                }
            }
        })
    }

    private fun actualizarVela(
        symbol: String, open: Double, high: Double, low: Double, close: Double,
        volume: Double, closeTime: Long, isClosed: Boolean, duration: Duration
    ) {
        val series = marketData[symbol] ?: return
        val endZoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(closeTime), ZoneId.of("UTC"))

        // Precisión extrema con DecimalNum
        val bar = BaseBar(
            duration,
            endZoned,
            DecimalNum.valueOf(open.toString()),
            DecimalNum.valueOf(high.toString()),
            DecimalNum.valueOf(low.toString()),
            DecimalNum.valueOf(close.toString()),
            DecimalNum.valueOf(volume.toString()),
            DecimalNum.valueOf("0.0")
        )

        try {
            if (series.barCount > 0 && series.lastBar.endTime.isEqual(endZoned)) {
                series.addBar(bar, true) // Actualiza la vela viva
            } else if (isClosed) {
                series.addBar(bar) // Agrega la vela ya cerrada
                evaluateStrategy(symbol, series) // Evaluar la estrategia en el momento del cierre de vela
            }
        } catch (e: Exception) {
            Log.e("Scanner", "Error actualizando serie $symbol: ${e.message}")
        }
    }

    private fun evaluateStrategy(symbol: String, series: BaseBarSeries) {
        if (series.barCount < 200) return

        // --- 1. CÁLCULO DE INDICADORES ---
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

        // =====================================================
        // PANEL MODO DEBUG (TEXTO PARA LA CONSOLA DE LA APP)
        // =====================================================
        val hora = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        val logMensaje = """
            [$hora] --- ANALIZANDO: $symbol ---
            Precio: ${String.format(Locale.US, "%.5f", closeActual)} USDT | ATR: ${String.format(Locale.US, "%.3f", atrPct)}% (Req: >=${config.minVolatilidadPct}%)
            EMA7: ${String.format(Locale.US, "%.5f", ema7Actual)} | EMA200: ${String.format(Locale.US, "%.5f", ema200Actual)}
            RSI: ${String.format(Locale.US, "%.1f", rsiActual)} | ADX: ${String.format(Locale.US, "%.1f", adxActual)}
        """.trimIndent()
        // =====================================================

        // --- 2. FILTROS DE RECHAZO ---
        if (atrPct < config.minVolatilidadPct) {
            emitirLogApp("$logMensaje\n❌ RECHAZADO: Mercado sin volatilidad\n----------------------------")
            return
        }

        // --- 3. GATILLO FRANCOTIRADOR ---
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
            emitirLogApp("$logMensaje\n✅ ¡OPORTUNIDAD DETECTADA!: $senal\n----------------------------")
            notificarAlerta(symbol, senal, closeActual, atrActual)
        } else {
            emitirLogApp("$logMensaje\n⏸️ RECHAZADO: Sin cruce o indicadores no alineados\n----------------------------")
        }
    }

    private fun notificarAlerta(symbol: String, senal: String, precio: Double, atrValue: Double) {
        var distSl = atrValue * config.multiplicadorSl

        // Topes de seguridad exactos
        val topeMax = precio * 0.008
        val topeMin = precio * 0.003
        if (distSl > topeMax) distSl = topeMax
        if (distSl < topeMin) distSl = topeMin

        val distTp = distSl * config.multiplicadorTp
        val tp = if (senal == "LONG") precio + distTp else precio - distTp
        val sl = if (senal == "LONG") precio - distSl else precio + distSl

        // 1. Enviar Alerta al Monitor Gráfico (Jetpack Compose - AlertManager)
        val nuevaAlerta = AlertData(
            symbol = symbol,
            senal = senal,
            precio = precio,
            tp = tp,
            sl = sl,
            velasEst = 2,
            timeframe = config.timeframe
        )
        AlertManager.agregarAlerta(nuevaAlerta)

        // 2. Enviar Notificación Push Local del sistema Android
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
        isRunning = false // Le avisa al sistema que el switch de apagado fue presionado
        webSocket?.close(1000, "Servicio detenido por el usuario")
        emitirLogApp("🛑 Motor de escaneo detenido.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}