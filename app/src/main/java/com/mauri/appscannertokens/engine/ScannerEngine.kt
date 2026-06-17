package com.mauri.appscannertokens.engine

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ta4j.core.BaseBar
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

class ScannerEngine(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val accountService: BinanceAccountService,
    private val marketDataService: BinanceMarketDataService,
    private val tickerWebSocket: BinanceTickerWebSocket,
    private val klineWebSocket: BinanceKlineWebSocket,
    private val strategyAnalyzer: StrategyAnalyzer,
    private val signalValidator: SignalValidator,
    private val riskCalculator: RiskCalculator,
    private val alertRepository: AlertRepository,
    private val logRepository: LogRepository,
    private val cooldownMap: ConcurrentHashMap<String, Long> = ConcurrentHashMap<String, Long>(),
    private val COOLDOWN_DURATION_MS: Long = 40 * 60 * 1000L
) {
    private val marketData = ConcurrentHashMap<String, TokenData>()
    private val spreadCache = ConcurrentHashMap<String, Double>()
    private var walletUsdt = 20.0

    suspend fun run() = coroutineScope {
        val initialConfig = configRepository.load()
        val duration = parseTimeframe(initialConfig.timeframe)

        logRepository.add("Filtrando contratos Perpetuos USDT...")
        ScannerDebugLogger.d("Iniciando scanner: cargando top pares de Binance Futures")
        val symbols = marketDataService.fetchTopPerpetualUsdtSymbols()
        val symbolSet = symbols.toSet()
        ScannerDebugLogger.d("Top pares seleccionados (${symbols.size}): ${symbols.joinToString(", ")}")

        logRepository.add("Descargando historial de ${symbols.size} tokens...")
        symbols.forEach { marketData[it] = TokenData(it) }

        symbols.map { symbol ->
            async(Dispatchers.IO) {
                loadHistory(symbol, initialConfig.timeframe, duration)
            }
        }.forEach { it.await() }

        logRepository.add("Iniciando WebSockets y bucle de scanner...")
        ScannerDebugLogger.d("Historial inicial descargado. Arrancando WebSockets de ticker y klines.")
        tickerWebSocket.start()
        klineWebSocket.start(symbols, initialConfig.timeframe, duration, marketData)

        launch { updateWalletLoop() }
        launch { updateSpreadLoop(symbolSet) }

        while (currentCoroutineContext().isActive && TradingScannerService.isRunning) {
            val config = configRepository.load()
            for (symbol in symbols) {
                if (!currentCoroutineContext().isActive || !TradingScannerService.isRunning) break
                val lastAlertTime = cooldownMap[symbol] ?: 0L
                if (System.currentTimeMillis() - lastAlertTime < COOLDOWN_DURATION_MS) {
                    continue // Salta la moneda inmediatamente, no gasta CPU
                }
                val tokenData = marketData[symbol] ?: continue
                val barCount = tokenData.series.barCount
                val spread = spreadCache[symbol] ?: 0.0
                val wsPrice = tickerWebSocket.price(symbol)
                ScannerDebugLogger.d(
                    "Revisando $symbol | velas=$barCount/$MIN_BARS | spread=${formatPct(spread)} | precioWs=${wsPrice ?: "sin dato"}"
                )

                if (barCount >= MIN_BARS) {
                    analyzeSymbol(symbol, tokenData, config)
                } else {
                    ScannerDebugLogger.d("Saltando $symbol: historial insuficiente ($barCount/$MIN_BARS velas)")
                }

                delay(100)
            }
            delay(2000)
        }
    }

    fun stop() {
        klineWebSocket.stop()
    }

    private suspend fun analyzeSymbol(symbol: String, tokenData: TokenData, config: UserConfig) {
        val spread = spreadCache[symbol] ?: 0.0
        val analysis = strategyAnalyzer.analyze(symbol, tokenData, config, spread)
        if (TradingScannerService.isDebugMode && analysis.debugLog.isNotBlank()) {
            logRepository.add(analysis.debugLog)
        }
        if (analysis.debugLog.isNotBlank()) {
            ScannerDebugLogger.d(analysis.debugLog)
        }

        when (analysis) {
            is StrategyAnalysis.Rejected -> {
                logRepository.add(analysis.reason)
                ScannerDebugLogger.d("$symbol rechazado por estrategia: ${analysis.reason}")
            }
            is StrategyAnalysis.Signal -> {
                ScannerDebugLogger.d("$symbol genero senal tecnica ${analysis.signal.side}; validando orderbook/trades/riesgo")
                validateAndPublish(analysis.signal, config)
            }
        }
    }

    private suspend fun validateAndPublish(signal: TechnicalSignal, config: UserConfig) {
        logRepository.add("Validando fuerza y orderbook para ${signal.symbol}...")
        when (val validation = signalValidator.validate(signal)) {
            is SignalValidationResult.Rejected -> {
                logRepository.add(validation.reason)
                ScannerDebugLogger.d("${signal.symbol} rechazado en validacion final: ${validation.reason}")
            }
            SignalValidationResult.Valid -> {
                when (val risk = riskCalculator.calculate(signal, walletUsdt, config)) {
                    is RiskCalculationResult.Rejected -> {
                        logRepository.add(risk.reason)
                        ScannerDebugLogger.d("${signal.symbol} rechazado por riesgo: ${risk.reason}")
                    }
                    is RiskCalculationResult.Approved -> {
                        logRepository.add("OPORTUNIDAD ${risk.alert.senal} DETECTADA EN ${risk.alert.symbol}")
                        ScannerDebugLogger.d(
                            "OPORTUNIDAD ${risk.alert.senal} ${risk.alert.symbol}: entrada=${risk.alert.precio}, tp=${risk.alert.tp}, sl=${risk.alert.sl}, wallet=$walletUsdt"
                        )
                        signalValidator.markAccepted(risk.alert.symbol)
                        alertRepository.add(risk.alert)
                        AlertNotifier.playNotification(context)
                        cooldownMap[risk.alert.symbol] = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private suspend fun updateWalletLoop() {
        while (currentCoroutineContext().isActive && TradingScannerService.isRunning) {
            val config = configRepository.load()
            if (config.apiKey.isNotBlank() && config.apiSecret.isNotBlank()) {
                val balance = accountService.getAvailableUsdtBalance(config.apiKey, config.apiSecret)
                if (balance > 0.0) walletUsdt = balance
            }
            delay(60_000)
        }
    }

    private suspend fun updateSpreadLoop(symbols: Set<String>) {
        while (currentCoroutineContext().isActive && TradingScannerService.isRunning) {
            spreadCache.putAll(marketDataService.fetchSpreads(symbols))
            delay(4000)
        }
    }

    private suspend fun loadHistory(symbol: String, timeframe: String, duration: Duration) {
        val tokenData = marketData[symbol] ?: return
        val klines = marketDataService.fetchKlines(symbol, timeframe)
        ScannerDebugLogger.d("Historial $symbol descargado: ${klines.size} velas timeframe=$timeframe")

        synchronized(tokenData) {
            klines.forEach { kline ->
                tokenData.series.addBar(
                    BaseBar(
                        duration,
                        ZonedDateTime.ofInstant(Instant.ofEpochMilli(kline.closeTime), ZoneId.of("UTC")),
                        tokenData.series.numOf(kline.open),
                        tokenData.series.numOf(kline.high),
                        tokenData.series.numOf(kline.low),
                        tokenData.series.numOf(kline.close),
                        tokenData.series.numOf(kline.volume),
                        tokenData.series.numOf(0.0)
                    )
                )
            }
        }
    }

    private fun parseTimeframe(timeframe: String): Duration = when (timeframe) {
        "1m" -> Duration.ofMinutes(1)
        "3m" -> Duration.ofMinutes(3)
        "5m" -> Duration.ofMinutes(5)
        "15m" -> Duration.ofMinutes(15)
        else -> Duration.ofMinutes(5)
    }

    private fun formatPct(value: Double): String =
        String.format(java.util.Locale.US, "%.4f%%", value)

    private companion object {
        const val MIN_BARS = 200
    }
}
