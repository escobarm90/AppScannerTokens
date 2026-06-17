package com.mauri.appscannertokens.engine

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import org.ta4j.core.num.Num
import java.util.Locale

class StrategyAnalyzer {
    fun analyze(
        symbol: String,
        tokenData: TokenData,
        config: UserConfig,
        spreadPct: Double
    ): StrategyAnalysis {
        val snapshot = synchronized(tokenData) {
            val idx = tokenData.series.endIndex
            val prev = idx - 1
            val ante = idx - 2
            if (ante < 0) return StrategyAnalysis.Rejected("", "Historial insuficiente")

            StrategySnapshot(
                closeActual = (tokenData.series.lastBar.closePrice as Num).doubleValue(),
                closeCerrada = (tokenData.closePrice.getValue(prev) as Num).doubleValue(),
                closePrevia = (tokenData.closePrice.getValue(ante) as Num).doubleValue(),
                rsiActual = (tokenData.rsi.getValue(prev) as Num).doubleValue(),
                adxActual = (tokenData.adx.getValue(prev) as Num).doubleValue(),
                atrActual = (tokenData.atr.getValue(prev) as Num).doubleValue(),
                ema7Actual = (tokenData.ema7.getValue(prev) as Num).doubleValue(),
                ema7Previa = (tokenData.ema7.getValue(ante) as Num).doubleValue(),
                ema200Actual = (tokenData.ema200.getValue(prev) as Num).doubleValue(),
                volCerrado = (tokenData.volumeInd.getValue(prev) as Num).doubleValue(),
                volSma = (tokenData.volSma.getValue(prev) as Num).doubleValue(),
                macdHistAct = (tokenData.macdLine.getValue(prev) as Num).doubleValue() -
                    (tokenData.macdSignal.getValue(prev) as Num).doubleValue(),
                macdHistPrev = (tokenData.macdLine.getValue(ante) as Num).doubleValue() -
                    (tokenData.macdSignal.getValue(ante) as Num).doubleValue(),
                bbUpperActual = (tokenData.bbUpper.getValue(prev) as Num).doubleValue(),
                bbLowerActual = (tokenData.bbLower.getValue(prev) as Num).doubleValue()
            )
        }

        val atrPct = if (snapshot.closeActual > 0) (snapshot.atrActual / snapshot.closeActual) * 100 else 0.0
        val volRatio = if (snapshot.volSma > 0) snapshot.volCerrado / snapshot.volSma else 0.0
        val debugLog = buildDebugLog(symbol, snapshot, atrPct, volRatio, spreadPct, config)

        if (spreadPct > config.spreadMaximo) return StrategyAnalysis.Rejected(debugLog, "ESTADO: RECHAZADO - SPREAD ALTO")
        if (atrPct < config.atrMinimo) return StrategyAnalysis.Rejected(debugLog, "ESTADO: RECHAZADO - MERCADO PLANO")
        if (volRatio < config.volumenRatioMinimo) return StrategyAnalysis.Rejected(debugLog, "ESTADO: RECHAZADO - VOLUMEN BAJO")

        val tendenciaAlcista = snapshot.closeCerrada > snapshot.ema200Actual
        val tendenciaBajista = snapshot.closeCerrada < snapshot.ema200Actual
        val gatilloLong = snapshot.closePrevia <= snapshot.ema7Previa && snapshot.closeCerrada > snapshot.ema7Actual
        val gatilloShort = snapshot.closePrevia >= snapshot.ema7Previa && snapshot.closeCerrada < snapshot.ema7Actual

        val side = when {
            tendenciaAlcista &&
                gatilloLong &&
                snapshot.adxActual >= 18 &&
                snapshot.rsiActual < 65 &&
                snapshot.macdHistAct > snapshot.macdHistPrev -> "LONG"

            tendenciaBajista &&
                gatilloShort &&
                snapshot.adxActual >= 18 &&
                snapshot.rsiActual > 35 &&
                snapshot.macdHistAct < snapshot.macdHistPrev -> "SHORT"

            else -> return StrategyAnalysis.Rejected(debugLog, "ESTADO: SIN SENAL TECNICA")
        }

        if (side == "LONG" && snapshot.closeCerrada >= snapshot.bbUpperActual) {
            return StrategyAnalysis.Rejected(debugLog, "RECHAZADO: EXTENUACION ALCISTA")
        }
        if (side == "SHORT" && snapshot.closeCerrada <= snapshot.bbLowerActual) {
            return StrategyAnalysis.Rejected(debugLog, "RECHAZADO: EXTENUACION BAJISTA")
        }

        return StrategyAnalysis.Signal(
            debugLog = debugLog,
            signal = TechnicalSignal(
                symbol = symbol,
                side = side,
                entryPrice = snapshot.closeActual,
                atr = snapshot.atrActual,
                spreadPct = spreadPct,
                timeframe = config.timeframe
            )
        )
    }

    private fun buildDebugLog(
        symbol: String,
        snapshot: StrategySnapshot,
        atrPct: Double,
        volRatio: Double,
        spreadPct: Double,
        config: UserConfig
    ): String = """
        --- ANALIZANDO: $symbol ---
        Precio: ${snapshot.closeActual} | RSI: ${String.format(Locale.US, "%.2f", snapshot.rsiActual)}
        ATR (%): ${String.format(Locale.US, "%.3f", atrPct)}% (Req: >= ${config.atrMinimo}%)
        ADX (14): ${String.format(Locale.US, "%.2f", snapshot.adxActual)} (Req: >= 18)
        BB UPPER: ${String.format(Locale.US, "%.5f", snapshot.bbUpperActual)} | BB LOWER: ${String.format(Locale.US, "%.5f", snapshot.bbLowerActual)}
        Vol Ratio: ${String.format(Locale.US, "%.2f", volRatio)}x (Req: >= ${config.volumenRatioMinimo}x)
        Spread: ${String.format(Locale.US, "%.3f", spreadPct)}% (Req: <= ${config.spreadMaximo}%)
    """.trimIndent()

    private data class StrategySnapshot(
        val closeActual: Double,
        val closeCerrada: Double,
        val closePrevia: Double,
        val rsiActual: Double,
        val adxActual: Double,
        val atrActual: Double,
        val ema7Actual: Double,
        val ema7Previa: Double,
        val ema200Actual: Double,
        val volCerrado: Double,
        val volSma: Double,
        val macdHistAct: Double,
        val macdHistPrev: Double,
        val bbUpperActual: Double,
        val bbLowerActual: Double
    )
}
