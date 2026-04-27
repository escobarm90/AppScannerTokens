package com.mauri.appscannertokens

import kotlin.math.abs
import kotlin.math.min

object OrderSizingCalculator {
    fun calculate(
        alert: AlertData,
        config: UserConfig,
        walletUsdt: Double,
        walletPercent: Double
    ): OrderSizing {
        if (alert.precio <= 0 || walletUsdt <= 0) {
            return OrderSizing(
                marginUsdt = 0.0,
                notionalUsdt = 0.0,
                quantity = 0.0,
                grossPnlUsdt = 0.0,
                netPnlUsdt = 0.0,
                roePct = 0.0,
                entryFeeUsdt = 0.0,
                exitFeeUsdt = 0.0,
                riskWasReduced = false
            )
        }

        val desiredMargin = walletUsdt * (walletPercent / 100.0)
        var notional = desiredMargin * config.apalancamiento
        var margin = desiredMargin
        var riskWasReduced = false

        val stopDistance = abs(alert.sl - alert.precio)
        if (stopDistance > 0) {
            val potentialLoss = stopDistance * (notional / alert.precio)
            val riskPct = min(config.riesgoMaximoBilletera, RiskLimits.MAX_WALLET_RISK_PCT)
            val maxRisk = walletUsdt * (riskPct / 100.0)
            if (potentialLoss > maxRisk) {
                val safeQuantity = maxRisk / stopDistance
                notional = safeQuantity * alert.precio
                margin = notional / config.apalancamiento
                riskWasReduced = true
            }
        }

        val takeProfitPct = abs(alert.tp - alert.precio) / alert.precio
        val grossPnl = notional * takeProfitPct
        val roePct = takeProfitPct * config.apalancamiento * 100.0
        val entryFee = notional * 0.0005
        val exitFee = notional * 0.0005
        val netPnl = grossPnl - entryFee - exitFee

        return OrderSizing(
            marginUsdt = margin,
            notionalUsdt = notional,
            quantity = notional / alert.precio,
            grossPnlUsdt = grossPnl,
            netPnlUsdt = netPnl,
            roePct = roePct,
            entryFeeUsdt = entryFee,
            exitFeeUsdt = exitFee,
            riskWasReduced = riskWasReduced
        )
    }
}
