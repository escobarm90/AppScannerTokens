package com.mauri.appscannertokens

import kotlinx.coroutines.delay
import kotlin.math.abs

class StopLossGuard(
    private val accountService: BinanceAccountService,
    private val orderService: BinanceOrderService,
    private val marketDataService: BinanceMarketDataService,
    private val tickerWebSocket: BinanceTickerWebSocket,
    private val logRepository: LogRepository,
    private val calculator: StopLossCalculator
) {
    suspend fun calculateAndPlaceInitial(
        config: UserConfig,
        position: ActivePosition,
        snapshot: BinancePositionSnapshot,
        exitSide: String
    ): StopLossProtectionResult {
        val entryPrice = snapshot.entryPrice.takeIf { it > 0.0 } ?: position.entryPrice
        val currentPrice = currentPrice(position.symbol, snapshot)
        val wallet = walletBalance(config)
        val plan = calculator.calculate(
            side = position.side,
            entryPrice = entryPrice,
            currentPrice = currentPrice,
            atr = position.atr,
            quantity = abs(snapshot.positionAmt),
            walletUsdt = wallet,
            config = config
        ) ?: return StopLossProtectionResult.EmergencyCloseRequired(
            reason = "No se pudo calcular SL seguro para ${position.symbol}",
            quantity = abs(snapshot.positionAmt)
        )

        val targetStop = mostProtectiveStop(position.side, position.currentSl, plan.stopPrice)
        logRepository.add(
            "SL inicial ${position.symbol}: stop=$targetStop, riesgoMax=${plan.riskPct}% (${plan.maxLossUsdt} USDT)."
        )
        ScannerDebugLogger.d(
            "SL inicial ${position.symbol}: entry=$entryPrice current=$currentPrice atr=${position.atr} qty=${abs(snapshot.positionAmt)} stop=$targetStop riskPct=${plan.riskPct}"
        )

        return placeVerifiedStopLoss(
            config = config,
            symbol = position.symbol,
            side = position.side,
            exitSide = exitSide,
            entryPrice = entryPrice,
            currentPrice = currentPrice,
            quantity = abs(snapshot.positionAmt),
            stopPrice = targetStop,
            previousStopOrderId = position.stopLossOrderId,
            emergencyOnFailure = true
        )
    }

    suspend fun moveStopLoss(
        config: UserConfig,
        position: ActivePosition,
        exitSide: String,
        entryPrice: Double,
        currentPrice: Double,
        quantity: Double,
        stopPrice: Double,
        previousStopOrderId: Long
    ): StopLossProtectionResult {
        return placeVerifiedStopLoss(
            config = config,
            symbol = position.symbol,
            side = position.side,
            exitSide = exitSide,
            entryPrice = entryPrice,
            currentPrice = currentPrice,
            quantity = quantity,
            stopPrice = stopPrice,
            previousStopOrderId = previousStopOrderId,
            emergencyOnFailure = false
        )
    }

    private suspend fun placeVerifiedStopLoss(
        config: UserConfig,
        symbol: String,
        side: String,
        exitSide: String,
        entryPrice: Double,
        currentPrice: Double,
        quantity: Double,
        stopPrice: Double,
        previousStopOrderId: Long,
        emergencyOnFailure: Boolean
    ): StopLossProtectionResult {
        if (quantity <= 0.0) {
            return StopLossProtectionResult.NoPosition("No hay cantidad abierta para proteger en $symbol")
        }
        if (calculator.isAlreadyBreached(side, currentPrice, stopPrice)) {
            return StopLossProtectionResult.EmergencyCloseRequired(
                reason = "El mercado ya cruzo el SL de $symbol antes de colocarlo. current=$currentPrice stop=$stopPrice",
                quantity = quantity
            )
        }

        repeat(MAX_STOP_ATTEMPTS) { attempt ->
            val livePrice = currentPrice(symbol, null)
            if (livePrice > 0.0 && calculator.isAlreadyBreached(side, livePrice, stopPrice)) {
                return StopLossProtectionResult.EmergencyCloseRequired(
                    reason = "El mercado cruzo el SL de $symbol durante la proteccion. current=$livePrice stop=$stopPrice",
                    quantity = quantity
                )
            }

            val result = orderService.placeClosePositionOrder(
                apiKey = config.apiKey,
                apiSecret = config.apiSecret,
                symbol = symbol,
                side = exitSide,
                type = "STOP_MARKET",
                stopPrice = stopPrice
            )
            ScannerDebugLogger.d(
                "SL Binance intento ${attempt + 1}/$MAX_STOP_ATTEMPTS $symbol: success=${result.success} code=${result.code} orderId=${result.orderId} msg=${result.message}"
            )

            if (result.success) {
                delay(VERIFY_DELAY_MS)
                val openStop = orderService.getOpenOrders(config.apiKey, config.apiSecret, symbol)
                    ?.firstOrNull {
                        it.type == "STOP_MARKET" &&
                            it.side == exitSide &&
                            it.closePosition &&
                            (it.orderId == result.orderId || it.clientOrderId == result.clientOrderId)
                    }
                if (openStop != null) {
                    orderService.cancelOpenOrdersExcept(config.apiKey, config.apiSecret, symbol, setOf(openStop.orderId))
                    logRepository.add("SL fisico verificado en Binance para $symbol. Orden ${openStop.orderId}.")
                    return StopLossProtectionResult.Protected(
                        stopPrice = openStop.stopPrice.takeIf { it > 0.0 } ?: result.stopPrice,
                        stopOrderId = openStop.orderId,
                        entryPrice = entryPrice,
                        currentPrice = livePrice.takeIf { it > 0.0 } ?: currentPrice,
                        positionAmount = quantity
                    )
                }
            }

            if (attempt < MAX_STOP_ATTEMPTS - 1) {
                delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }

        val existing = orderService.findOpenStopLoss(config.apiKey, config.apiSecret, symbol, exitSide)
        if (existing != null && isAtLeastAsProtective(side, existing.stopPrice, stopPrice)) {
            logRepository.add("Se conserva SL existente para $symbol. Orden ${existing.orderId}.")
            return StopLossProtectionResult.Protected(
                stopPrice = existing.stopPrice,
                stopOrderId = existing.orderId,
                entryPrice = entryPrice,
                currentPrice = currentPrice,
                positionAmount = quantity
            )
        }

        val reason = "No se pudo verificar SL fisico en Binance para $symbol despues de $MAX_STOP_ATTEMPTS intentos."
        return if (emergencyOnFailure || previousStopOrderId <= 0L) {
            StopLossProtectionResult.EmergencyCloseRequired(reason, quantity)
        } else {
            StopLossProtectionResult.NoPosition(reason)
        }
    }

    private suspend fun walletBalance(config: UserConfig): Double {
        val total = accountService.getTotalUsdtBalance(config.apiKey, config.apiSecret)
        if (total > 0.0) return total
        val available = accountService.getAvailableUsdtBalance(config.apiKey, config.apiSecret)
        return if (available > 0.0) available else config.billeteraManual
    }

    private suspend fun currentPrice(symbol: String, snapshot: BinancePositionSnapshot?): Double {
        return snapshot?.markPrice?.takeIf { it > 0.0 }
            ?: tickerWebSocket.price(symbol)
            ?: marketDataService.fetchCurrentPrice(symbol)
    }

    private fun isAtLeastAsProtective(side: String, existingStop: Double, targetStop: Double): Boolean {
        val isLong = side.uppercase() in listOf("LONG", "BUY")
        return if (isLong) existingStop >= targetStop else existingStop <= targetStop
    }

    private fun mostProtectiveStop(side: String, candidateStop: Double, fallbackStop: Double): Double {
        if (candidateStop <= 0.0) return fallbackStop
        val isLong = side.uppercase() in listOf("LONG", "BUY")
        return if (isLong) {
            maxOf(candidateStop, fallbackStop)
        } else {
            minOf(candidateStop, fallbackStop)
        }
    }

    private companion object {
        const val MAX_STOP_ATTEMPTS = 5
        const val VERIFY_DELAY_MS = 300L
        const val RETRY_DELAY_MS = 700L
    }
}
