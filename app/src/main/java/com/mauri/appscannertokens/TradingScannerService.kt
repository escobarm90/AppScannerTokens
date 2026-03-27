package com.mauri.appscannertokens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.ta4j.core.BarSeries
import org.ta4j.core.BaseBarSeriesBuilder
import org.ta4j.core.indicators.ATRIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.averages.EMAIndicator
import org.ta4j.core.indicators.averages.SMAIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.num.DoubleNum
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class TradingScannerService : Service() {

    companion object {
        private const val CHANNEL_ID = "ScannerServiceChannel"
        private const val TOP_VOLATILES = 50
        private const val VOLUMEN_MIN_24H = 15000000.0
    }

    private val prefs by lazy { getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    private var isScanning = false
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val mercado: MutableMap<String, BarSeries> = ConcurrentHashMap()

    override fun onCreate() {
        super.onCreate()
        try {
            crearCanalNotificacion()
        } catch (e: Exception) {
            enviarDebug("⚠️ Error al crear canal: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Motor Scanner Activo")
                .setContentText("Monitoreando pares en vivo...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notification)
            }

            if (!isScanning) {
                iniciarMotorBinance()
                isScanning = true
            }
        } catch (e: Exception) {
            enviarDebug("🚨 CRASH PREVENIDO en onStartCommand: ${e.message}")
        }

        return START_STICKY
    }

    private fun iniciarMotorBinance() {
        val tframe = prefs.getString(PrefKeys.TIMEFRAME, "3m") ?: "3m"
        val duracion = cuandoTimeframeADuration(tframe)

        Thread {
            try {
                enviarDebug("🚀 Iniciando Motor REST (Temporalidad: $tframe)...")
                val topPares = obtenerTopPares()
                enviarDebug("✅ Top ${topPares.size} pares filtrados. Descargando historial...")

                for (symbol in topPares) {
                    descargarVelas(symbol, tframe, duracion)
                    Thread.sleep(50)
                }

                enviarDebug("✅ 250 velas descargadas por par.")
                enviarDebug("🔌 Conectando WebSockets en vivo...")

                iniciarWebsocketMultiplex(topPares, tframe, duracion)

            } catch (e: Exception) {
                enviarDebug("❌ Error fatal en Motor: ${e.message}")
            }
        }.start()
    }

    private fun obtenerTopPares(): List<String> {
        val validTickers = mutableListOf<JsonObject>()
        val request = Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/24hr").build()

        return try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: return emptyList()
                val tickers = JsonParser.parseString(json).asJsonArray

                for (elem in tickers) {
                    val t = elem.asJsonObject
                    val symbol = t.get("symbol").asString
                    val volume = t.get("quoteVolume").asDouble

                    if (symbol.endsWith("USDT") && volume > VOLUMEN_MIN_24H) {
                        validTickers.add(t)
                    }
                }

                validTickers.sortByDescending { abs(it.get("priceChangePercent").asDouble) }
                validTickers.take(TOP_VOLATILES).map { it.get("symbol").asString }
            }
        } catch (e: Exception) {
            enviarDebug("⚠️ Error al obtener tickers: ${e.message}")
            emptyList()
        }
    }

    private fun descargarVelas(symbol: String, timeframe: String, duracion: Duration) {
        val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$timeframe&limit=250"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                val json = response.body?.string() ?: return
                val klines = JsonParser.parseString(json).asJsonArray

                // 🔥 SOLUCIÓN: Pasamos la clase de Java directamente. 100% compatible con Kotlin
                val serie = BaseBarSeriesBuilder()
                    .withName(symbol)
                    .withMaxBarCount(250)
                    .withNumTypeOf(DoubleNum::class.java)
                    .build()

                for (elem in klines) {
                    val k = elem.asJsonArray
                    val closeTime = k.get(6).asLong

                    val open = k.get(1).asDouble
                    val high = k.get(2).asDouble
                    val low = k.get(3).asDouble
                    val close = k.get(4).asDouble
                    val vol = k.get(5).asDouble

                    val endTime = Instant.ofEpochMilli(closeTime)
                    val beginTime = endTime.minus(duracion)

                    serie.addBar(
                        org.ta4j.core.BaseBar(
                            duracion,
                            beginTime,
                            endTime,
                            DoubleNum.valueOf(open),
                            DoubleNum.valueOf(high),
                            DoubleNum.valueOf(low),
                            DoubleNum.valueOf(close),
                            DoubleNum.valueOf(vol),
                            DoubleNum.valueOf(0.0),
                            0L
                        )
                    )
                }
                mercado[symbol] = serie
            }
        } catch (e: Exception) {
            enviarDebug("⚠️ Error al descargar $symbol: ${e.message}")
        }
    }

    private fun iniciarWebsocketMultiplex(pares: List<String>, timeframe: String, duracion: Duration) {
        val streams = pares.joinToString("/") { "${it.lowercase()}@kline_$timeframe" }
        val wssUrl = "wss://fstream.binance.com/stream?streams=$streams"
        val request = Request.Builder().url(wssUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    if (!json.has("data")) return

                    val data = json.getAsJsonObject("data")
                    val symbol = data.get("s").asString
                    val kline = data.getAsJsonObject("k")

                    if (kline.get("i").asString != timeframe) return
                    if (!mercado.containsKey(symbol)) return

                    val closeTime = kline.get("T").asLong

                    val open = kline.get("o").asDouble
                    val high = kline.get("h").asDouble
                    val low = kline.get("l").asDouble
                    val close = kline.get("c").asDouble
                    val vol = kline.get("v").asDouble

                    val endTime = Instant.ofEpochMilli(closeTime)
                    val beginTime = endTime.minus(duracion)
                    val serie = mercado[symbol] ?: return

                    val vela = org.ta4j.core.BaseBar(
                        duracion,
                        beginTime,
                        endTime,
                        DoubleNum.valueOf(open),
                        DoubleNum.valueOf(high),
                        DoubleNum.valueOf(low),
                        DoubleNum.valueOf(close),
                        DoubleNum.valueOf(vol),
                        DoubleNum.valueOf(0.0),
                        0L
                    )

                    if (serie.barCount > 0 && serie.lastBar.endTime == endTime) {
                        serie.addBar(vela, true)
                    } else {
                        serie.addBar(vela, false)
                    }

                    calcularIndicadores(symbol, serie)
                } catch (e: Exception) {
                    enviarDebug("⚠️ Error procesando WS: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                enviarDebug("⚠️ WebSocket falló: ${t.message}. Reintentando en 10s...")
                Thread.sleep(10000)
                if (isScanning) iniciarMotorBinance()
            }
        })
    }

    private fun obtenerSaldoBilleteraUSDT(): Double {
        val apiKey = prefs.getString(PrefKeys.API_KEY, "") ?: ""
        val secretKey = prefs.getString(PrefKeys.SECRET_KEY, "") ?: ""
        val saldoManual = prefs.getFloat(PrefKeys.BILLETERA_MANUAL, 20.0f).toDouble()

        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            return saldoManual
        }

        return try {
            val timestamp = System.currentTimeMillis()
            val queryString = "timestamp=$timestamp"

            val sha256HMAC = Mac.getInstance("HmacSHA256")
            val secretKeySpec = SecretKeySpec(secretKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            sha256HMAC.init(secretKeySpec)
            val hash = sha256HMAC.doFinal(queryString.toByteArray(StandardCharsets.UTF_8))

            val signature = hash.joinToString("") { "%02x".format(it) }

            val url = "https://fapi.binance.com/fapi/v2/balance?$queryString&signature=$signature"
            val request = Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val balances = JsonParser.parseString(response.body!!.string()).asJsonArray
                    for (b in balances) {
                        val balance = b.asJsonObject
                        if (balance.get("asset").asString == "USDT") {
                            return balance.get("balance").asDouble
                        }
                    }
                }
            }
            saldoManual
        } catch (e: Exception) {
            enviarDebug("⚠️ Error API Binance: ${e.message}. Usando saldo manual.")
            saldoManual
        }
    }

    private fun calcularIndicadores(symbol: String, series: BarSeries) {
        if (series.barCount < 50) return

        val ultima = series.endIndex
        val previa = ultima - 1

        val closePrice = ClosePriceIndicator(series)
        val rsi = RSIIndicator(closePrice, 14)
        val ema9 = EMAIndicator(closePrice, 9)
        val ema200 = EMAIndicator(closePrice, 200)
        val atr = ATRIndicator(series, 14)

        val sma20 = SMAIndicator(closePrice, 20)
        val sd20 = StandardDeviationIndicator(closePrice, 20)
        val bbMiddle = BollingerBandsMiddleIndicator(sma20)

        val bbUpper = BollingerBandsUpperIndicator(bbMiddle, sd20, DoubleNum.valueOf(2.0))
        val bbLower = BollingerBandsLowerIndicator(bbMiddle, sd20, DoubleNum.valueOf(2.0))

        val precioActual = closePrice.getValue(ultima).doubleValue()
        val precioApertura = series.getBar(ultima).openPrice.doubleValue()
        val precioMinimo = series.getBar(ultima).lowPrice.doubleValue()
        val precioMaximo = series.getBar(ultima).highPrice.doubleValue()

        val valorEma9 = ema9.getValue(ultima).doubleValue()
        val valorEma200 = ema200.getValue(ultima).doubleValue()
        val valorBbLower = bbLower.getValue(ultima).doubleValue()
        val valorBbUpper = bbUpper.getValue(ultima).doubleValue()
        val valorAtr = atr.getValue(ultima).doubleValue()

        val atrP = if (precioActual > 0) (valorAtr / precioActual) * 100 else 0.0

        val cierrePrevio = closePrice.getValue(previa).doubleValue()
        val minPrevio = series.getBar(previa).lowPrice.doubleValue()
        val maxPrevio = series.getBar(previa).highPrice.doubleValue()
        val ema9Previa = ema9.getValue(previa).doubleValue()
        val bbLowerPrevio = bbLower.getValue(previa).doubleValue()
        val bbUpperPrevio = bbUpper.getValue(previa).doubleValue()

        val velaVerde = precioActual > precioApertura
        val velaRoja = precioActual < precioApertura

        val toqueBbInf = (minPrevio <= bbLowerPrevio) || (precioMinimo <= valorBbLower)
        val recuperaEma9 = (precioActual > valorEma9) && (cierrePrevio <= ema9Previa)

        val zonaBbUpper = (maxPrevio >= bbUpperPrevio) || (precioMaximo >= valorBbUpper)
        val pierdeEma9 = (precioActual < valorEma9) && (cierrePrevio >= ema9Previa)

        var senal = "NEUTRAL"
        if (toqueBbInf && recuperaEma9 && velaVerde) senal = "LONG"
        else if (zonaBbUpper && pierdeEma9 && velaRoja) senal = "SHORT"

        val senalPrevia = senal
        var razonRechazo = ""

        if (senal != "NEUTRAL") {
            val billeteraReal = obtenerSaldoBilleteraUSDT()
            val tamanoPosPct = prefs.getFloat(PrefKeys.TAMANO_POS_PCT, 30.0f) / 100.0
            val apalancamiento = prefs.getFloat(PrefKeys.APALANCAMIENTO, 20.0f).toDouble()
            val perdidaMaxPct = prefs.getFloat(PrefKeys.PERDIDA_MAX_PCT, 5.0f) / 100.0
            val roiMinimo = prefs.getFloat(PrefKeys.ROI_MINIMO, 5.0f).toDouble()

            val margenPorOperacion = billeteraReal * tamanoPosPct
            val tamanoPosicionReal = margenPorOperacion * apalancamiento

            if (precioActual > 0) {
                val cantidadTokens = tamanoPosicionReal / precioActual
                val perdidaMaximaUSDT = billeteraReal * perdidaMaxPct
                val distSl = if (cantidadTokens > 0) perdidaMaximaUSDT / cantidadTokens else 0.0
                val distTp = valorAtr * 2.0

                val tp = if (senal == "LONG") precioActual + distTp else precioActual - distTp
                val sl = if (senal == "LONG") precioActual - distSl else precioActual + distSl

                val gananciaBrutaUSDT = cantidadTokens * distTp
                val feeEntrada = tamanoPosicionReal * 0.0005
                val feeSalida = (cantidadTokens * tp) * 0.0005
                val pnlNeto = gananciaBrutaUSDT - (feeEntrada + feeSalida)

                val roi = if (margenPorOperacion > 0) (pnlNeto / margenPorOperacion) * 100 else 0.0

                if (atrP < 0.12) {
                    razonRechazo = "RECHAZADO - MERCADO PLANO"
                    senal = "NEUTRAL"
                } else if (roi < roiMinimo) {
                    razonRechazo = String.format(Locale.US, "RECHAZADO - ROI %.2f%% < Mínimo %.2f%%", roi, roiMinimo)
                    senal = "NEUTRAL"
                } else {
                    razonRechazo = "APROBADO ✅"

                    val tituloPush = "${if (senal == "LONG") "🟢" else "🔴"} $senal en $symbol | PNL: $${String.format(Locale.US, "%.2f", pnlNeto)} USDT"
                    val cuerpoPush = """
                        💵 ENTRADA: ${String.format(Locale.US, "%.4f", precioActual)}
                        ✅ TP: ${String.format(Locale.US, "%.4f", tp)}
                        ❌ SL: ${String.format(Locale.US, "%.4f", sl)}
                        🏦 BILLETERA: $${String.format(Locale.US, "%.2f", billeteraReal)} USDT
                        💰 MARGEN: $${String.format(Locale.US, "%.2f", margenPorOperacion)} USDT
                        🔥 ROI EST: ${String.format(Locale.US, "%.2f%%", roi)}
                    """.trimIndent()

                    val intent = Intent("NUEVA_ALERTA")
                    intent.setPackage(packageName)
                    intent.putExtra("titulo", tituloPush)
                    intent.putExtra("cuerpo", cuerpoPush)
                    sendBroadcast(intent)

                    hacerVibrar(senal)
                }
            }
        }

        val debugMsj = """
            ╭────────────────────────────────────────╮
            │ 🪙 TOKEN: $symbol (USDT)
            │ 💵 PRECIO: ${String.format(Locale.US, "%.4f", precioActual)}
            │ 📊 RSI (14): ${String.format(Locale.US, "%.2f", rsi.getValue(ultima).doubleValue())}
            │ 📈 EMA 9: ${String.format(Locale.US, "%.4f", valorEma9)}
            │ 📉 EMA 200: ${String.format(Locale.US, "%.4f", valorEma200)}
            │ 📏 ATR (%): ${String.format(Locale.US, "%.3f%%", atrP)}
            │ 🎯 SEÑAL: $senalPrevia
            │ 🛡️ ESTADO: ${if (razonRechazo.isEmpty()) "ESPERANDO" else razonRechazo}
            ╰────────────────────────────────────────╯
        """.trimIndent()

        enviarDebug(debugMsj)
    }

    private fun hacerVibrar(senal: String) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            val patronLong = longArrayOf(0, 150, 100, 150)
            val patronShort = longArrayOf(0, 600)
            val patron = if (senal == "LONG") patronLong else patronShort

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(patron, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(patron, -1)
            }
        }
    }

    private fun enviarDebug(msj: String) {
        val intent = Intent("NUEVO_DEBUG")
        intent.setPackage(packageName)
        intent.putExtra("linea_debug", msj)
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
        webSocket?.cancel()
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Escáner Nativo", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    private fun cuandoTimeframeADuration(tf: String): Duration = when(tf) {
        "1m" -> Duration.ofMinutes(1)
        "3m" -> Duration.ofMinutes(3)
        "5m" -> Duration.ofMinutes(5)
        "15m" -> Duration.ofMinutes(15)
        "30m" -> Duration.ofMinutes(30)
        "1h" -> Duration.ofHours(1)
        "4h" -> Duration.ofHours(4)
        "1d" -> Duration.ofDays(1)
        else -> Duration.ofMinutes(3) // Por defecto
    }
}