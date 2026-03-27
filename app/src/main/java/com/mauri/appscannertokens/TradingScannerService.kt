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
import org.ta4j.core.indicators.helpers.VolumeIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.num.DoubleNum
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// Estructura para almacenar temporalmente los datos del Order Flow
data class OrderFlowData(
    val spread: Double,
    val bidQty: Double,
    val askQty: Double,
    val pctCompras: Double
)

class TradingScannerService : Service() {

    companion object {
        private const val CHANNEL_ID = "ScannerServiceChannel"
        private const val TOP_VOLATILES = 50
        private const val VOLUMEN_MIN_24H = 15000000.0
        private const val SPREAD_MAXIMO = 0.15
        private const val VOL_RATIO_MINIMO = 1.0
    }

    private val prefs by lazy { getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE) }
    private var isScanning = false
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val mercado: MutableMap<String, BarSeries> = ConcurrentHashMap()
    private val ultimaAlertaPorToken = ConcurrentHashMap<String, Long>()

    // Caché de Billetera para no saturar la API
    private var ultimoSaldoCache: Double = 0.0
    private var ultimaVezSaldoActualizado: Long = 0L

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
                .setContentText("Analizando Microestructura de Mercado...")
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
                enviarDebug("🚀 Iniciando Motor (Temporalidad: $tframe)...")
                val topPares = obtenerTopPares()
                enviarDebug("✅ Top ${topPares.size} pares filtrados. Descargando historial...")

                for (symbol in topPares) {
                    descargarVelas(symbol, tframe, duracion)
                    Thread.sleep(50)
                }

                enviarDebug("✅ Historial descargado. Conectando WebSockets en vivo...")
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

                    // Ignoramos BTC, ETH y Stablecoins
                    if (symbol.endsWith("USDT") &&
                        !symbol.contains("BTCUSDT") &&
                        !symbol.contains("ETHUSDT") &&
                        !symbol.contains("USDCUSDT") &&
                        !symbol.contains("FDUSDUSDT") &&
                        volume > VOLUMEN_MIN_24H) {
                        validTickers.add(t)
                    }
                }

                // Ordenar por volatilidad de 24h y agarrar los top 50
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

                val velas = mutableListOf<org.ta4j.core.Bar>()

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

                    velas.add(
                        org.ta4j.core.BaseBar(
                            duracion, beginTime, endTime,
                            DoubleNum.valueOf(open), DoubleNum.valueOf(high), DoubleNum.valueOf(low),
                            DoubleNum.valueOf(close), DoubleNum.valueOf(vol), DoubleNum.valueOf(0.0), 0L
                        )
                    )
                }

                val serie = BaseBarSeriesBuilder().withName(symbol).withMaxBarCount(250).withBars(velas).build()
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
                        duracion, beginTime, endTime,
                        DoubleNum.valueOf(open), DoubleNum.valueOf(high), DoubleNum.valueOf(low),
                        DoubleNum.valueOf(close), DoubleNum.valueOf(vol), DoubleNum.valueOf(0.0), 0L
                    )

                    if (serie.barCount > 0 && serie.lastBar.endTime == endTime) {
                        serie.addBar(vela, true)
                    } else {
                        serie.addBar(vela, false)
                    }

                    analizarOportunidad(symbol, serie)
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

    private fun calcularOrderFlow(symbol: String): OrderFlowData? {
        try {
            // L2 Orderbook - Profundidad
            val reqOb = Request.Builder().url("https://fapi.binance.com/fapi/v1/depth?symbol=$symbol&limit=5").build()
            val resOb = client.newCall(reqOb).execute()
            if (!resOb.isSuccessful) return null
            val jsonOb = JsonParser.parseString(resOb.body?.string()).asJsonObject

            val bids = jsonOb.getAsJsonArray("bids")
            val asks = jsonOb.getAsJsonArray("asks")

            var bidQty = 0.0
            for (i in 0 until bids.size()) bidQty += bids.get(i).asJsonArray.get(1).asDouble

            var askQty = 0.0
            for (i in 0 until asks.size()) askQty += asks.get(i).asJsonArray.get(1).asDouble

            val bestBid = bids.get(0).asJsonArray.get(0).asDouble
            val bestAsk = asks.get(0).asJsonArray.get(0).asDouble
            val spread = if (bestBid > 0) ((bestAsk - bestBid) / bestBid) * 100 else 99.0

            // Flujo de Trades Recientes (Tape Reading)
            val reqTrades = Request.Builder().url("https://fapi.binance.com/fapi/v1/trades?symbol=$symbol&limit=20").build()
            val resTrades = client.newCall(reqTrades).execute()
            if (!resTrades.isSuccessful) return null
            val jsonTrades = JsonParser.parseString(resTrades.body?.string()).asJsonArray

            var volCompras = 0.0
            var volTotal = 0.0
            for (i in 0 until jsonTrades.size()) {
                val trade = jsonTrades.get(i).asJsonObject
                val qty = trade.get("qty").asDouble
                val isBuyerMaker = trade.get("isBuyerMaker").asBoolean
                volTotal += qty
                if (!isBuyerMaker) volCompras += qty // Comprador Taker (agresivo)
            }
            val pctCompras = if (volTotal > 0) (volCompras / volTotal) * 100 else 50.0

            return OrderFlowData(spread, bidQty, askQty, pctCompras)
        } catch (e: Exception) {
            return null
        }
    }

    private fun obtenerSaldoBilleteraUSDT(): Double {
        val saldoManual = prefs.getFloat(PrefKeys.BILLETERA_MANUAL, 20.0f).toDouble()
        val apiKey = prefs.getString(PrefKeys.API_KEY, "") ?: ""
        val secretKey = prefs.getString(PrefKeys.SECRET_KEY, "") ?: ""

        if (apiKey.isEmpty() || secretKey.isEmpty()) {
            enviarSaldoAMainActivity(saldoManual)
            return saldoManual
        }

        val tiempoActual = System.currentTimeMillis()
        if (tiempoActual - ultimaVezSaldoActualizado < 30_000L && ultimoSaldoCache > 0.0) {
            return ultimoSaldoCache
        }

        return try {
            val queryString = "timestamp=$tiempoActual"
            val sha256HMAC = Mac.getInstance("HmacSHA256")
            val secretKeySpec = SecretKeySpec(secretKey.trim().toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            sha256HMAC.init(secretKeySpec)
            val hash = sha256HMAC.doFinal(queryString.toByteArray(StandardCharsets.UTF_8))
            val signature = hash.joinToString("") { "%02x".format(it) }

            val url = "https://fapi.binance.com/fapi/v2/balance?$queryString&signature=$signature"
            val request = Request.Builder().url(url).addHeader("X-MBX-APIKEY", apiKey.trim()).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val balances = JsonParser.parseString(response.body!!.string()).asJsonArray
                    for (b in balances) {
                        val balance = b.asJsonObject
                        if (balance.get("asset").asString == "USDT") {
                            val saldoReal = balance.get("balance").asDouble
                            ultimoSaldoCache = saldoReal
                            ultimaVezSaldoActualizado = tiempoActual
                            enviarSaldoAMainActivity(saldoReal)
                            return saldoReal
                        }
                    }
                }
            }
            enviarSaldoAMainActivity(saldoManual)
            saldoManual
        } catch (e: Exception) {
            enviarSaldoAMainActivity(saldoManual)
            saldoManual
        }
    }

    private fun enviarSaldoAMainActivity(saldo: Double) {
        val intent = Intent("NUEVO_SALDO")
        intent.setPackage(packageName)
        intent.putExtra("saldo", saldo)
        sendBroadcast(intent)
    }

    private fun analizarOportunidad(symbol: String, series: BarSeries) {
        if (series.barCount < 50) return

        val ultima = series.endIndex
        val previa = ultima - 1

        val closePrice = ClosePriceIndicator(series)
        val rsi = RSIIndicator(closePrice, 14)
        val ema7 = EMAIndicator(closePrice, 7)
        val ema200 = EMAIndicator(closePrice, 200)
        val atr = ATRIndicator(series, 14)

        val volumenInd = VolumeIndicator(series)
        val volSma = SMAIndicator(volumenInd, 20)

        val sma20 = SMAIndicator(closePrice, 20)
        val sd20 = StandardDeviationIndicator(closePrice, 20)
        val bbMiddle = BollingerBandsMiddleIndicator(sma20)

        val bbUpper = BollingerBandsUpperIndicator(bbMiddle, sd20, DoubleNum.valueOf(2.0))
        val bbLower = BollingerBandsLowerIndicator(bbMiddle, sd20, DoubleNum.valueOf(2.0))

        val precioActual = closePrice.getValue(ultima).doubleValue()
        val precioApertura = series.getBar(ultima).openPrice.doubleValue()
        val precioMinimo = series.getBar(ultima).lowPrice.doubleValue()
        val precioMaximo = series.getBar(ultima).highPrice.doubleValue()
        val volumenActual = volumenInd.getValue(ultima).doubleValue()
        val volPromedio = volSma.getValue(ultima).doubleValue()

        val valorEma200 = ema200.getValue(ultima).doubleValue()
        val valorEma7 = ema7.getValue(ultima).doubleValue()
        val ema7Previa = ema7.getValue(previa).doubleValue()
        val valorBbLower = bbLower.getValue(ultima).doubleValue()
        val valorBbUpper = bbUpper.getValue(ultima).doubleValue()
        val valorAtr = atr.getValue(ultima).doubleValue()

        // 1. Análisis de Volatilidad Relativa
        val atrP = if (precioActual > 0) (valorAtr / precioActual) * 100 else 0.0
        val volRatio = if (volPromedio > 0) volumenActual / volPromedio else 0.0

        val cierrePrevio = closePrice.getValue(previa).doubleValue()
        val minPrevio = series.getBar(previa).lowPrice.doubleValue()
        val maxPrevio = series.getBar(previa).highPrice.doubleValue()
        val bbLowerPrevio = bbLower.getValue(previa).doubleValue()
        val bbUpperPrevio = bbUpper.getValue(previa).doubleValue()

        val velaVerde = precioActual > precioApertura
        val velaRoja = precioActual < precioApertura

        // 2. Patrones Técnicos Reversión a la Media
        val toqueBbInf = (minPrevio <= bbLowerPrevio) || (precioMinimo <= valorBbLower)
        val recuperaEma7 = (cierrePrevio < ema7Previa) && (precioActual > valorEma7)

        val toqueBbSup = (maxPrevio >= bbUpperPrevio) || (precioMaximo >= valorBbUpper)
        val pierdeEma7 = (cierrePrevio > ema7Previa) && (precioActual < valorEma7)

        var senalTecnica = "NEUTRAL"
        if (toqueBbInf && recuperaEma7 && velaVerde) senalTecnica = "LONG"
        else if (toqueBbSup && pierdeEma7 && velaRoja) senalTecnica = "SHORT"

        // 3. Variables de Riesgo del Usuario (Actualizadas en tiempo real)
        val billeteraReal = obtenerSaldoBilleteraUSDT()
        val P = prefs.getFloat(PrefKeys.TAMANO_POS_PCT, 10.0f) / 100.0 // Margen
        val L = prefs.getFloat(PrefKeys.APALANCAMIENTO, 20.0f).toDouble() // Leverage
        val R = prefs.getFloat(PrefKeys.PERDIDA_MAX_PCT, 2.0f) / 100.0 // Stop Loss Riesgo Total
        val roiMinimo = prefs.getFloat(PrefKeys.ROI_MINIMO, 5.0f).toDouble()
        val tipoMargen = prefs.getString(PrefKeys.TIPO_MARGEN, "ISOLATED") ?: "ISOLATED"
        val RR = 2.0 // Ratio Riesgo Beneficio (Fijo para TP matemático)

        val margenPorOperacion = billeteraReal * P

        // Cálculo del ATR Mínimo necesario matemáticamente para alcanzar el ROI esperado
        val atrMinimoRequerido = (roiMinimo + (L * 0.1)) / (L * 2.0)

        var razonRechazo = ""
        var infoOrderFlow = "No evaluado (Sin señal)"

        if (senalTecnica != "NEUTRAL") {
            val tiempoActual = System.currentTimeMillis()
            val tiempoUltima = ultimaAlertaPorToken[symbol] ?: 0L

            if ((tiempoActual - tiempoUltima) < 60_000L) {
                razonRechazo = "RECHAZADO - EN COOLDOWN (1 MIN)"
            } else if (volRatio <= VOL_RATIO_MINIMO) {
                razonRechazo = String.format(Locale.US, "RECHAZADO - VOLUMEN %.2fx <= %.2fx", volRatio, VOL_RATIO_MINIMO)
            } else if (atrP < atrMinimoRequerido) {
                razonRechazo = String.format(Locale.US, "RECHAZADO - MERCADO PLANO (ATR: %.2f%% < Req: %.2f%%)", atrP, atrMinimoRequerido)
            } else {
                // 4. Confirmación por Order Flow y Liquidez
                val orderFlow = calcularOrderFlow(symbol)

                if (orderFlow == null) {
                    razonRechazo = "RECHAZADO - ERROR LECTURA API BINANCE"
                } else {
                    infoOrderFlow = String.format(Locale.US, "Bid: %.1f / Ask: %.1f | Fuerza Compra: %.1f%%", orderFlow.bidQty, orderFlow.askQty, orderFlow.pctCompras)

                    if (orderFlow.spread > SPREAD_MAXIMO) {
                        razonRechazo = String.format(Locale.US, "RECHAZADO - SPREAD ALTO (%.3f%% > %.2f%%)", orderFlow.spread, SPREAD_MAXIMO)
                    } else if (senalTecnica == "LONG" && orderFlow.pctCompras < 45.0) {
                        razonRechazo = String.format(Locale.US, "RECHAZADO LONG - FALTAN COMPRADORES (%.1f%% < 45%%)", orderFlow.pctCompras)
                    } else if (senalTecnica == "SHORT" && orderFlow.pctCompras > 55.0) {
                        razonRechazo = String.format(Locale.US, "RECHAZADO SHORT - FALTAN VENDEDORES (%.1f%% > 55%%)", orderFlow.pctCompras)
                    } else {
                        // 5. Aprobación y Aplicación Estricta de Fórmulas Matemáticas
                        razonRechazo = "APROBADO ✅"
                        ultimaAlertaPorToken[symbol] = tiempoActual
                        val horaExacta = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tiempoActual))

                        val PE = precioActual
                        val sl: Double
                        val tp: Double

                        // FÓRMULA DE RIESGO: Adaptada a LONG y SHORT
                        if (senalTecnica == "LONG") {
                            sl = PE * (1.0 - (R / (P * L)))
                            tp = PE + (PE - sl) * RR
                        } else {
                            sl = PE * (1.0 + (R / (P * L)))
                            tp = PE - (sl - PE) * RR
                        }

                        val distTp = abs(tp - PE)
                        val tamanoPosicionReal = margenPorOperacion * L
                        val cantidadTokens = tamanoPosicionReal / PE

                        val gananciaBrutaUSDT = cantidadTokens * distTp
                        val feeEntrada = tamanoPosicionReal * 0.0005
                        val feeSalida = (cantidadTokens * tp) * 0.0005
                        val pnlNeto = gananciaBrutaUSDT - (feeEntrada + feeSalida)
                        val roi = if (margenPorOperacion > 0) (pnlNeto / margenPorOperacion) * 100 else 0.0

                        val pnlInfoStr = "PNL: $${String.format(Locale.US, "%.2f", pnlNeto)} USDT"

                        val cuerpoPush = """
                            ⚡ APALANCAMIENTO: ${L.toInt()}x ($tipoMargen)
                            💵 ENTRADA: ${String.format(Locale.US, "%.4f", PE)}
                            ✅ TP: ${String.format(Locale.US, "%.4f", tp)}
                            ❌ SL: ${String.format(Locale.US, "%.4f", sl)}
                            💰 MARGEN: $${String.format(Locale.US, "%.2f", margenPorOperacion)} USDT
                            🔥 ROI EST: ${String.format(Locale.US, "%.2f%%", roi)}
                        """.trimIndent()

                        val intent = Intent("NUEVA_ALERTA")
                        intent.setPackage(packageName)
                        intent.putExtra("token", symbol)
                        intent.putExtra("senal", senalTecnica)
                        intent.putExtra("hora", horaExacta)
                        intent.putExtra("pnl_info", pnlInfoStr)
                        intent.putExtra("cuerpo", cuerpoPush)
                        sendBroadcast(intent)

                        hacerVibrar(senalTecnica)
                    }
                }
            }
        }

        // Formato vertical estricto para el Cmd Scanner
        val debugMsj = """
            ╭────────────────────────────────────────╮
            │ 🪙 TOKEN: $symbol (USDT)
            │ 💵 PRECIO ACTUAL: ${String.format(Locale.US, "%.6f", precioActual)}
            │ 📊 RSI (14): ${String.format(Locale.US, "%.2f", rsi.getValue(ultima).doubleValue())}
            │ 📈 EMA 7: ${String.format(Locale.US, "%.6f", valorEma7)}
            │ 📉 EMA 200: ${String.format(Locale.US, "%.6f", valorEma200)}
            │ 📏 ATR (%): ${String.format(Locale.US, "%.3f%%", atrP)} (Mín Req: ${String.format(Locale.US, "%.3f%%", atrMinimoRequerido)})
            │ 🎢 BB L/U: ${String.format(Locale.US, "%.6f", valorBbLower)} / ${String.format(Locale.US, "%.6f", valorBbUpper)}
            │ 🌊 VOL RATIO: ${String.format(Locale.US, "%.2fx", volRatio)}
            │ 🚀 ORDER FLOW: $infoOrderFlow
            │ 🎯 SEÑAL TÉCNICA: $senalTecnica
            │ 🛡️ ESTADO: ${if (razonRechazo.isEmpty()) "ESPERANDO CONFIRMACIÓN" else razonRechazo}
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
        else -> Duration.ofMinutes(3)
    }
}