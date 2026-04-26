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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ta4j.core.BaseBar
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TradingScannerService : Service() {

    companion object {
        var isRunning = false
        var isDebugMode = true
    }

    private lateinit var config: UserConfig
    private lateinit var wsManager: BinanceWebSocketManager
    private lateinit var strategyAnalyzer: StrategyAnalyzer

    private val marketData = ConcurrentHashMap<String, TokenData>()
    private val registroBloqueo = ConcurrentHashMap<String, Long>()
    private var billeteraUsdt = 20.0

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        config = cargarConfiguracion()
        isRunning = true

        // 1. Inicializar Manager de WebSockets
        wsManager = BinanceWebSocketManager(config, marketData) { AlertManager.agregarLog(it) }

        // 2. Inicializar el Analizador con el log corregido para ver en Logcat y App
        strategyAnalyzer = StrategyAnalyzer(
            this,
            config,
            BinanceApiManager.preciosWs, // Usando el caché del Manager
            registroBloqueo,
            billeteraUsdt
        ) { mensaje ->
            Log.d("ScannerDebug", mensaje) // Imprime en Android Studio
            AlertManager.agregarLog(mensaje) // Imprime en la consola de la App
        }

        startForeground(1, crearNotificacion())

        CoroutineScope(Dispatchers.IO).launch {
            iniciarEscaner()
        }

        return START_STICKY
    }

    private suspend fun iniciarEscaner() = coroutineScope {
        AlertManager.agregarLog("🚀 Filtrando contratos Perpetuos USDT...")

        val topSymbols = obtenerTopPares()
        val duration = parseTimeframe(config.timeframe)

        AlertManager.agregarLog("⏳ Descargando historial de ${topSymbols.size} tokens...")

        topSymbols.forEach { marketData[it] = TokenData(it) }

        // Descarga de historial multihilo para rapidez inicial
        val executor = java.util.concurrent.Executors.newFixedThreadPool(5)
        topSymbols.forEach { symbol ->
            executor.submit { descargarHistorial(symbol, config.timeframe, duration) }
        }
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.MINUTES)

        AlertManager.agregarLog("✅ Iniciando Websockets y Bucle Secuencial...")

        // Iniciamos flujos de datos en tiempo real
        wsManager.conectar(topSymbols, duration)
        BinanceApiManager.iniciarWsTickers()

        // Lanzamos actualización de saldo en segundo plano
        launch { actualizarBilletera() }

        // BUCLE PRINCIPAL DE COORDINACIÓN (RESTAURADO CON RECARGA DINÁMICA)
        launch {
            while (isRunning) {
                // RECARGA DINÁMICA: Leemos la config del almacenamiento y la actualizamos en el analizador
                config = cargarConfiguracion()
                strategyAnalyzer.actualizarConfig(config)

                for (symbol in topSymbols) {
                    if (!isRunning) break

                    val data = marketData[symbol]

                    // Log de control para ver el estado de cada moneda en el Logcat
                    Log.d("ScannerDebug", "Revisando $symbol | Velas: ${data?.series?.barCount ?: 0}/200")

                    if (data != null && data.series.barCount >= 200) {
                        try {
                            strategyAnalyzer.analizar(symbol, data)
                        } catch (e: Exception) {
                            Log.e("ScannerDebug", "Error en análisis de $symbol: ${e.message}")
                        }
                    }
                    delay(100) // Pausa de 0.1s entre tokens (DELAY_ENTRE_TOKENS)
                }
                delay(2000) // Pausa de 2s entre ciclos completos
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
                        val quoteVol = it.get("quoteVolume").asString.toDoubleOrNull() ?: 0.0
                        s.endsWith("USDT") && !s.contains("_") && quoteVol > 15000000.0
                    }
                    .sortedByDescending { Math.abs(it.get("priceChangePercent").asString.toDoubleOrNull() ?: 0.0) }
                    .take(50)
                    .map { it.get("symbol").asString }
            }
        } catch (e: Exception) {
            Log.e("ScannerDebug", "Error obteniendo pares: ${e.message}")
            listOf("BTCUSDT", "ETHUSDT", "SOLUSDT")
        }
    }

    private fun descargarHistorial(symbol: String, timeframe: String, duration: Duration) {
        val tokenData = marketData[symbol] ?: return
        val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$timeframe&limit=300"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (!body.startsWith("[")) return

            val klines = JsonParser.parseString(body).asJsonArray
            for (i in 0 until klines.size()) {
                val kline = klines.get(i).asJsonArray
                val endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(kline.get(6).asLong), ZoneId.of("UTC"))

                val bar = BaseBar(duration, endTime,
                    tokenData.series.numOf(kline.get(1).asString.toDoubleOrNull() ?: 0.0),
                    tokenData.series.numOf(kline.get(2).asString.toDoubleOrNull() ?: 0.0),
                    tokenData.series.numOf(kline.get(3).asString.toDoubleOrNull() ?: 0.0),
                    tokenData.series.numOf(kline.get(4).asString.toDoubleOrNull() ?: 0.0),
                    tokenData.series.numOf(kline.get(5).asString.toDoubleOrNull() ?: 0.0),
                    tokenData.series.numOf(0.0)
                )
                tokenData.series.addBar(bar)
            }
        } catch (e: Exception) {
            Log.e("ScannerDebug", "Error Historial $symbol: ${e.message}")
        }
    }

    private suspend fun actualizarBilletera() {
        while (isRunning) {
            try {
                if (config.apiKey.isNotEmpty() && config.apiSecret.isNotEmpty()) {
                    val saldoReal = BinanceApiManager.obtenerSaldoUSDT(config.apiKey, config.apiSecret)
                    if (saldoReal > 0) {
                        billeteraUsdt = saldoReal
                    }
                }
            } catch (e: Exception) {
                Log.e("ScannerDebug", "Error actualizando billetera: ${e.message}")
            }
            delay(60000)
        }
    }

    private fun parseTimeframe(tf: String): Duration = when (tf) {
        "1m" -> Duration.ofMinutes(1)
        "3m" -> Duration.ofMinutes(3)
        "5m" -> Duration.ofMinutes(5)
        "15m" -> Duration.ofMinutes(15)
        else -> Duration.ofMinutes(5)
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("TradingScannerChannel", "Motor Scanner", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun crearNotificacion() = NotificationCompat.Builder(this, "TradingScannerChannel")
        .setContentTitle("Scanner Activo")
        .setContentText("Monitoreando mercados en tiempo real...")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()

    private fun cargarConfiguracion(): UserConfig {
        val prefs = getSharedPreferences("AppScannerConfig", Context.MODE_PRIVATE)
        val json = prefs.getString("config_data", null)
        return if (json != null) gson.fromJson(json, UserConfig::class.java) else UserConfig()
    }

    override fun onDestroy() {
        isRunning = false
        wsManager.cerrar()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}