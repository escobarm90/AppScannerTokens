package com.mauri.appscannertokens

import kotlin.math.abs
import kotlin.math.min

class StopLossCalculator {
    fun calculate(
        side: String,
        entryPrice: Double,
        currentPrice: Double,
        atr: Double,
        quantity: Double,
        walletUsdt: Double,
        config: UserConfig
    ): StopLossPlan? {
        if (entryPrice <= 0.0 || currentPrice <= 0.0 || quantity == 0.0 || walletUsdt <= 0.0) {
            return null
        }

        val riskPct = min(config.riesgoMaximoBilletera, RiskLimits.MAX_WALLET_RISK_PCT)
        val maxLossUsdt = walletUsdt * (riskPct / 100.0)
        val maxDistanceByRisk = maxLossUsdt / abs(quantity)
        val atrDistance = if (atr > 0.0) atr * config.multiplicadorSl else entryPrice * DEFAULT_STOP_DISTANCE_PCT
        val stopDistance = min(atrDistance, maxDistanceByRisk)
        if (stopDistance <= 0.0) return null

        val isLong = side.uppercase() in listOf("LONG", "BUY")
        val stopPrice = if (isLong) entryPrice - stopDistance else entryPrice + stopDistance
        return StopLossPlan(
            stopPrice = stopPrice,
            riskPct = riskPct,
            maxLossUsdt = maxLossUsdt,
            stopDistance = stopDistance
        )
    }

    fun isAlreadyBreached(side: String, currentPrice: Double, stopPrice: Double): Boolean {
        val isLong = side.uppercase() in listOf("LONG", "BUY")
        return if (isLong) currentPrice <= stopPrice else currentPrice >= stopPrice
    }

    private companion object {
        const val DEFAULT_STOP_DISTANCE_PCT = 0.005
    }
}
