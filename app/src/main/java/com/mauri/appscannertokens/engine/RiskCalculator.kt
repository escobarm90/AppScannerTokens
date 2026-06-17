package com.mauri.appscannertokens.engine

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import kotlin.math.min
import kotlin.math.max

class RiskCalculator {
    fun calculate(
        signal: TechnicalSignal,
        walletUsdt: Double,
        config: UserConfig
    ): RiskCalculationResult {
        val effectiveWallet = if (walletUsdt > 0) walletUsdt else config.billeteraManual
        var stopDistance = signal.atr * config.multiplicadorSl

        val riskPct = min(config.riesgoMaximoBilletera, RiskLimits.MAX_WALLET_RISK_PCT)
        val riskUsdt = effectiveWallet * (riskPct / 100.0)
        val marginUsdt = effectiveWallet * (config.porcentajeInversion / 100.0)
        val positionSizeUsdt = marginUsdt * config.apalancamiento

        val maxStopDistance = if (positionSizeUsdt > 0) {
            (riskUsdt / positionSizeUsdt) * signal.entryPrice
        } else {
            signal.entryPrice * 0.005
        }

        if (stopDistance > maxStopDistance) stopDistance = maxStopDistance

        val absoluteSpread = (signal.spreadPct / 100.0) * signal.entryPrice
        if (stopDistance <= absoluteSpread * 1.5) {
            return RiskCalculationResult.Rejected(
                "RECHAZADO: SL ($stopDistance) es menor al spread critico ($absoluteSpread)"
            )
        }

        val takeProfitDistance = stopDistance * config.multiplicadorTp
        val takeProfit = if (signal.side == "LONG") {
            signal.entryPrice + takeProfitDistance
        } else {
            signal.entryPrice - takeProfitDistance
        }
        val stopLoss = if (signal.side == "LONG") {
            signal.entryPrice - stopDistance
        } else {
            signal.entryPrice + stopDistance
        }

        val estimatedBars = max(1, (takeProfitDistance / signal.atr).toInt())
        return RiskCalculationResult.Approved(
            AlertData(
                symbol = signal.symbol,
                senal = signal.side,
                precio = signal.entryPrice,
                tp = takeProfit,
                sl = stopLoss,
                velasEstimadas = estimatedBars,
                timeframe = signal.timeframe,
                atr = signal.atr
            )
        )
    }
}
