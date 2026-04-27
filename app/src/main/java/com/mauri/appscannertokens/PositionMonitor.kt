package com.mauri.appscannertokens

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

class PositionMonitor(
    private val config: UserConfig,
    private val initialPosition: ActivePosition,
    private val accountService: BinanceAccountService,
    private val orderService: BinanceOrderService,
    private val marketDataService: BinanceMarketDataService,
    private val tickerWebSocket: BinanceTickerWebSocket,
    private val positionRepository: PositionRepository,
    private val logRepository: LogRepository,
    private val trailingStopEngine: TrailingStopEngine,
    private val stopLossGuard: StopLossGuard
) {
    suspend fun run(isResume: Boolean) {
        val symbol = initialPosition.symbol
        val isLong = initialPosition.side.uppercase() in listOf("LONG", "BUY")
        val exitSide = if (isLong) "SELL" else "BUY"

        var entryPrice = initialPosition.entryPrice
        var dynamicTp = initialPosition.dynamicTp
        var dynamicSl = initialPosition.currentSl
        var trailingLevel = initialPosition.trailingLevel
        var stopLossOrderId = initialPosition.stopLossOrderId
        var lastTrailingAt = 0L
        var lastPositionAmount = 0.0

        if (!isResume) {
            val openedSnapshot = waitUntilPositionOpens(symbol) ?: return
            val protection = stopLossGuard.calculateAndPlaceInitial(
                config = config,
                position = initialPosition,
                snapshot = openedSnapshot,
                exitSide = exitSide
            )
            val protectedState = applyProtectionResult(symbol, exitSide, protection) ?: return
            entryPrice = protectedState.entryPrice
            dynamicSl = protectedState.stopPrice
            stopLossOrderId = protectedState.stopOrderId
            lastPositionAmount = if (isLong) protectedState.quantity else -protectedState.quantity

            positionRepository.update(symbol) {
                it.copy(
                    entryPrice = entryPrice,
                    currentPrice = protectedState.currentPrice,
                    currentSl = dynamicSl,
                    dynamicTp = dynamicTp,
                    stopLossOrderId = stopLossOrderId,
                    quantity = protectedState.quantity
                )
            }
            placeTakeProfit(symbol, exitSide, dynamicTp)
        } else {
            val snapshot = accountService.getPositionSnapshot(config.apiKey, config.apiSecret, symbol)
            if (snapshot != null && snapshot.positionAmt != 0.0) {
                entryPrice = snapshot.entryPrice.takeIf { it > 0.0 } ?: entryPrice
                val currentPrice = currentPrice(symbol, snapshot)
                val protection = stopLossGuard.calculateAndPlaceInitial(
                    config = config,
                    position = initialPosition.copy(
                        entryPrice = entryPrice,
                        currentPrice = currentPrice,
                        currentSl = dynamicSl,
                        quantity = abs(snapshot.positionAmt)
                    ),
                    snapshot = snapshot,
                    exitSide = exitSide
                )
                val protectedState = applyProtectionResult(symbol, exitSide, protection) ?: return
                dynamicSl = protectedState.stopPrice
                stopLossOrderId = protectedState.stopOrderId
                lastPositionAmount = snapshot.positionAmt
                positionRepository.update(symbol) {
                    it.copy(
                        entryPrice = entryPrice,
                        currentPrice = protectedState.currentPrice,
                        currentSl = dynamicSl,
                        stopLossOrderId = stopLossOrderId,
                        quantity = abs(snapshot.positionAmt)
                    )
                }
                placeTakeProfit(symbol, exitSide, dynamicTp)
            }
        }

        logRepository.add("Vigilancia activa para $symbol.")
        while (currentCoroutineContext().isActive) {
            delay(300)

            val snapshot = accountService.getPositionSnapshot(config.apiKey, config.apiSecret, symbol)
            if (snapshot == null) {
                logRepository.add("No se pudo confirmar posicion $symbol; se mantiene vigilancia.")
                continue
            }

            lastPositionAmount = snapshot.positionAmt
            if (lastPositionAmount == 0.0) {
                markClosed(symbol)
                logRepository.add("Posicion $symbol cerrada en Binance.")
                break
            }

            val currentPrice = currentPrice(symbol, snapshot)
            if (currentPrice == 0.0) continue

            updatePositionPnl(symbol, currentPrice, lastPositionAmount, entryPrice)

            val hitSl = if (isLong) currentPrice <= dynamicSl else currentPrice >= dynamicSl
            val hitTp = if (isLong) currentPrice >= dynamicTp else currentPrice <= dynamicTp
            if (hitSl || hitTp) {
                val reason = if (hitSl) "SL virtual" else "TP virtual"
                logRepository.add("$reason alcanzado en $symbol. Ejecutando cierre MARKET de respaldo.")
                closeAtMarket(symbol, exitSide, abs(lastPositionAmount))
                markClosed(symbol)
                break
            }

            val trailingUpdate = trailingStopEngine.calculateUpdate(
                entryPrice = entryPrice,
                currentPrice = currentPrice,
                currentTp = dynamicTp,
                isLong = isLong,
                currentLevel = trailingLevel,
                lastUpdateAt = lastTrailingAt
            ) ?: continue

            if (marketAlreadyCrossed(symbol, isLong, trailingUpdate)) {
                closeAtMarket(symbol, exitSide, abs(lastPositionAmount))
                markClosed(symbol)
                break
            }

            val movedStop = stopLossGuard.moveStopLoss(
                config = config,
                position = initialPosition.copy(entryPrice = entryPrice, currentSl = dynamicSl),
                exitSide = exitSide,
                entryPrice = entryPrice,
                currentPrice = currentPrice,
                quantity = abs(lastPositionAmount),
                stopPrice = trailingUpdate.newSl,
                previousStopOrderId = stopLossOrderId
            )

            when (movedStop) {
                is StopLossProtectionResult.Protected -> {
                    dynamicSl = movedStop.stopPrice
                    stopLossOrderId = movedStop.stopOrderId
                    dynamicTp = trailingUpdate.newTp
                    trailingLevel = trailingUpdate.newLevel
                    lastTrailingAt = System.currentTimeMillis()

                    positionRepository.update(symbol) {
                        it.copy(
                            dynamicTp = dynamicTp,
                            currentSl = dynamicSl,
                            trailingLevel = trailingLevel,
                            stopLossOrderId = stopLossOrderId
                        )
                    }
                    placeTakeProfit(symbol, exitSide, dynamicTp)
                    logRepository.add("Trailing actualizado en $symbol. Nivel $trailingLevel.")
                }
                is StopLossProtectionResult.EmergencyCloseRequired -> {
                    logRepository.add("EMERGENCIA SL $symbol: ${movedStop.reason}")
                    closeAtMarket(symbol, exitSide, movedStop.quantity)
                    markClosed(symbol)
                    break
                }
                is StopLossProtectionResult.NoPosition -> {
                    logRepository.add("Trailing pausado en $symbol: ${movedStop.reason}")
                }
            }
        }
    }

    private suspend fun waitUntilPositionOpens(symbol: String): BinancePositionSnapshot? {
        repeat(900) {
            val snapshot = accountService.getPositionSnapshot(config.apiKey, config.apiSecret, symbol)
            val status = accountService.getOrderStatus(config.apiKey, config.apiSecret, symbol, initialPosition.orderId)

            if (snapshot != null && snapshot.positionAmt != 0.0) {
                if (status == "PARTIALLY_FILLED") {
                    orderService.cancelOrder(config.apiKey, config.apiSecret, symbol, initialPosition.orderId)
                    logRepository.add("Orden $symbol parcialmente ejecutada; remanente cancelado antes de proteger SL.")
                }
                logRepository.add("Posicion $symbol abierta. Colocando SL fisico prioritario.")
                return snapshot
            }

            if (status in listOf("CANCELED", "REJECTED", "EXPIRED")) {
                orderService.cancelOpenOrdersStrictly(config.apiKey, config.apiSecret, symbol)
                positionRepository.remove(symbol)
                logRepository.add("Orden $symbol cancelada o expirada.")
                return null
            }

            delay(500)
        }

        logRepository.add("Orden $symbol sigue pendiente; vigilancia pausada hasta reanudar.")
        return null
    }

    private suspend fun applyProtectionResult(
        symbol: String,
        exitSide: String,
        result: StopLossProtectionResult
    ): ProtectedState? {
        return when (result) {
            is StopLossProtectionResult.Protected -> {
                ProtectedState(
                    entryPrice = result.entryPrice,
                    currentPrice = result.currentPrice,
                    stopPrice = result.stopPrice,
                    stopOrderId = result.stopOrderId,
                    quantity = abs(result.positionAmount)
                )
            }
            is StopLossProtectionResult.EmergencyCloseRequired -> {
                logRepository.add("EMERGENCIA SL $symbol: ${result.reason}")
                closeAtMarket(symbol, exitSide, result.quantity)
                markClosed(symbol)
                null
            }
            is StopLossProtectionResult.NoPosition -> {
                logRepository.add(result.reason)
                null
            }
        }
    }

    private suspend fun placeTakeProfit(symbol: String, exitSide: String, takeProfit: Double) {
        val result = orderService.placeClosePositionOrder(
            apiKey = config.apiKey,
            apiSecret = config.apiSecret,
            symbol = symbol,
            side = exitSide,
            type = "TAKE_PROFIT_MARKET",
            stopPrice = takeProfit
        )
        if (result.success) {
            logRepository.add("TP fisico enviado para $symbol. Orden ${result.orderId}.")
        } else {
            logRepository.add("TP no colocado para $symbol: ${result.message}. SL fisico permanece activo.")
        }
    }

    private suspend fun marketAlreadyCrossed(
        symbol: String,
        isLong: Boolean,
        update: TrailingUpdate
    ): Boolean {
        val price = currentPrice(symbol, null)
        if (price == 0.0) return false
        return if (isLong) {
            price <= update.newSl || price >= update.newTp
        } else {
            price >= update.newSl || price <= update.newTp
        }
    }

    private suspend fun closeAtMarket(symbol: String, exitSide: String, quantity: Double) {
        orderService.cancelOpenOrdersStrictly(config.apiKey, config.apiSecret, symbol)
        orderService.executeGuaranteedClose(
            apiKey = config.apiKey,
            apiSecret = config.apiSecret,
            symbol = symbol,
            side = exitSide,
            quantity = quantity,
            marginType = config.tipoMargen
        )
        logRepository.add("Cierre MARKET ejecutado para $symbol.")
    }

    private fun updatePositionPnl(symbol: String, price: Double, amount: Double, entryPrice: Double) {
        val isLong = initialPosition.side.uppercase() in listOf("LONG", "BUY")
        val distance = if (isLong) price - entryPrice else entryPrice - price
        val pnl = distance * abs(amount)
        val margin = (entryPrice * abs(amount)) / initialPosition.apalancamiento
        val roe = if (margin > 0.0) (pnl / margin) * 100.0 else 0.0

        positionRepository.update(symbol) {
            it.copy(currentPrice = price, pnlNeto = pnl, roePct = roe)
        }
    }

    private suspend fun currentPrice(symbol: String, snapshot: BinancePositionSnapshot?): Double {
        return snapshot?.markPrice?.takeIf { it > 0.0 }
            ?: tickerWebSocket.price(symbol)
            ?: marketDataService.fetchCurrentPrice(symbol)
    }

    private fun markClosed(symbol: String) {
        positionRepository.update(symbol) { it.copy(isClosed = true) }
    }

    private data class ProtectedState(
        val entryPrice: Double,
        val currentPrice: Double,
        val stopPrice: Double,
        val stopOrderId: Long,
        val quantity: Double
    )
}
