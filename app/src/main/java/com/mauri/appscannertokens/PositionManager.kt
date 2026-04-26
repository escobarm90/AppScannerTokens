package com.mauri.appscannertokens

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object PositionManager {
    val positions: StateFlow<List<ActivePosition>> = PositionRepository.positions

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val monitorJobs = ConcurrentHashMap<String, Job>()

    fun initialize(context: Context) {
        AppGraph.initialize(context)
        PositionRepository.initialize(context)
        AppGraph.tickerWebSocket.start()

        PositionRepository.activePositions().forEach { position ->
            resumeMonitor(context.applicationContext, position)
        }
    }

    fun startMonitoring(
        context: Context,
        config: UserConfig,
        symbol: String,
        signal: String,
        entryPrice: Double,
        takeProfit: Double,
        stopLoss: Double,
        leverage: Int,
        orderId: Long,
        quantity: Double
    ) {
        if (PositionRepository.activePositions().any { it.symbol == symbol }) return

        val position = ActivePosition(
            symbol = symbol,
            side = signal,
            entryPrice = entryPrice,
            currentPrice = entryPrice,
            initialTp = takeProfit,
            dynamicTp = takeProfit,
            currentSl = stopLoss,
            apalancamiento = leverage,
            orderId = orderId,
            quantity = quantity
        )
        PositionRepository.add(position)
        LogRepository.add("Posicion agregada al monitor: $symbol")
        launchMonitor(context.applicationContext, config, position, isResume = false)
    }

    fun closePositionManual(context: Context, config: UserConfig, symbol: String) {
        scope.launch {
            AppGraph.initialize(context)
            LogRepository.add("Cierre manual iniciado para $symbol")
            AppGraph.orderService.cancelOpenOrdersStrictly(config.apiKey, config.apiSecret, symbol)

            val amount = AppGraph.accountService.getPositionAmount(config.apiKey, config.apiSecret, symbol)
            if (amount == null) {
                LogRepository.add("No se pudo confirmar cantidad activa en $symbol; no se marca como cerrada.")
                return@launch
            }

            if (amount != 0.0) {
                val exitSide = if (amount > 0) "SELL" else "BUY"
                AppGraph.orderService.executeGuaranteedClose(
                    apiKey = config.apiKey,
                    apiSecret = config.apiSecret,
                    symbol = symbol,
                    side = exitSide,
                    quantity = abs(amount),
                    marginType = config.tipoMargen
                )
                LogRepository.add("$symbol cerrada manualmente.")
            }

            PositionRepository.update(symbol) { it.copy(isClosed = true) }
        }
    }

    fun removePosition(symbol: String) {
        monitorJobs.remove(symbol)?.cancel()
        PositionRepository.remove(symbol)
    }

    private fun resumeMonitor(context: Context, position: ActivePosition) {
        if (monitorJobs[position.symbol]?.isActive == true) return
        val config = AppGraph.configRepository.load()
        scope.launch {
            val orderStatus = AppGraph.accountService.getOrderStatus(
                config.apiKey,
                config.apiSecret,
                position.symbol,
                position.orderId
            )
            val positionAmount = AppGraph.accountService.getPositionAmount(
                config.apiKey,
                config.apiSecret,
                position.symbol
            )

            if (positionAmount == 0.0 && orderStatus in listOf("CANCELED", "REJECTED", "EXPIRED")) {
                PositionRepository.remove(position.symbol)
                return@launch
            }

            launchMonitor(
                context = context,
                config = config,
                position = position,
                isResume = orderStatus == "FILLED" || (positionAmount != null && positionAmount != 0.0)
            )
        }
    }

    private fun launchMonitor(
        context: Context,
        config: UserConfig,
        position: ActivePosition,
        isResume: Boolean
    ) {
        if (monitorJobs[position.symbol]?.isActive == true) return

        monitorJobs[position.symbol] = scope.launch {
            PositionMonitor(
                context = context,
                config = config,
                initialPosition = position,
                accountService = AppGraph.accountService,
                orderService = AppGraph.orderService,
                marketDataService = AppGraph.marketDataService,
                tickerWebSocket = AppGraph.tickerWebSocket,
                positionRepository = PositionRepository,
                logRepository = LogRepository,
                trailingStopEngine = TrailingStopEngine()
            ).run(isResume)
        }
    }
}
