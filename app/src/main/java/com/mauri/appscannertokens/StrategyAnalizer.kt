package com.mauri.appscannertokens

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonParser
import org.ta4j.core.num.Num
import java.util.Locale

class StrategyAnalyzer(
    private val context: Context,
    private var config: UserConfig,
    private val spreadCache: Map<String, Double>,
    private val registroBloqueo: MutableMap<String, Long>,
    private var billeteraUsdt: Double,
    private val onLog: (String) -> Unit
) {
    // OkHttpClient exclusivo para las validaciones finales de esta clase
    private val client = OkHttpClient()

    fun actualizarConfig(nuevaConfig: UserConfig) {
        this.config = nuevaConfig
    }
    fun analizar(symbol: String, tData: TokenData) {
        val idx: Int; val prev: Int; val ante: Int
        val closeActual: Double; val closeCerrada: Double; val closePrevia: Double
        val rsiActual: Double; val adxActual: Double; val atrActual: Double
        val ema7Actual: Double; val ema7Previa: Double; val ema200Actual: Double
        val volCerrado: Double; val volSma: Double
        val macdHistAct: Double; val macdHistPrev: Double
        val bbUpperActual: Double; val bbLowerActual: Double

        synchronized(tData) {
            idx = tData.series.endIndex
            prev = idx - 1
            ante = idx - 2

            if (ante < 0) return

            // Casting explícito "as Num" para asegurar que Kotlin identifique la interfaz y su doubleValue()
            closeActual = (tData.series.lastBar.closePrice as Num).doubleValue()
            closeCerrada = (tData.closePrice.getValue(prev) as Num).doubleValue()
            closePrevia = (tData.closePrice.getValue(ante) as Num).doubleValue()

            rsiActual = (tData.rsi.getValue(prev) as Num).doubleValue()
            adxActual = (tData.adx.getValue(prev) as Num).doubleValue()
            atrActual = (tData.atr.getValue(prev) as Num).doubleValue()

            ema7Actual = (tData.ema7.getValue(prev) as Num).doubleValue()
            ema7Previa = (tData.ema7.getValue(ante) as Num).doubleValue()
            ema200Actual = (tData.ema200.getValue(prev) as Num).doubleValue()

            volCerrado = (tData.volumeInd.getValue(prev) as Num).doubleValue()
            volSma = (tData.volSma.getValue(prev) as Num).doubleValue()

            macdHistAct = (tData.macdLine.getValue(prev) as Num).doubleValue() - (tData.macdSignal.getValue(prev) as Num).doubleValue()
            macdHistPrev = (tData.macdLine.getValue(ante) as Num).doubleValue() - (tData.macdSignal.getValue(ante) as Num).doubleValue()
            bbUpperActual = (tData.bbUpper.getValue(prev) as Num).doubleValue()
            bbLowerActual = (tData.bbLower.getValue(prev) as Num).doubleValue()
        }

        val atrPct = if (closeActual > 0) (atrActual / closeActual * 100) else 0.0
        val spread = spreadCache[symbol] ?: 0.0
        val volRatio = if (volSma > 0) volCerrado / volSma else 0.0

        // Modificación: El log ahora muestra los valores dinámicos de config
        val log = """
            --- ANALIZANDO: $symbol ---
            Precio: $closeActual | RSI: ${String.format(Locale.US, "%.2f", rsiActual)}
            ATR (%): ${String.format(Locale.US, "%.3f", atrPct)}% (Req: >= ${config.atrMinimo}%)
            ADX (14): ${String.format(Locale.US, "%.2f", adxActual)} (Req: >= 18)
            BB UPPER: ${String.format(Locale.US, "%.5f", bbUpperActual)} | BB LOWER: ${String.format(Locale.US, "%.5f", bbLowerActual)}
            Vol Ratio: ${String.format(Locale.US, "%.2f", volRatio)}x (Req: >= ${config.volumenRatioMinimo}x)
            Spread: ${String.format(Locale.US, "%.3f", spread)}% (Req: <= ${config.spreadMaximo}%)
        """.trimIndent()

        onLog(log)

        // Modificación: Los rechazos técnicos ahora usan las variables de configuración
        if (spread > config.spreadMaximo) { onLog("ESTADO: RECHAZADO - SPREAD ALTO"); return }
        if (atrPct < config.atrMinimo) { onLog("ESTADO: RECHAZADO - MERCADO PLANO"); return }
        if (volRatio < config.volumenRatioMinimo) { onLog("ESTADO: RECHAZADO - VOLUMEN BAJO"); return }

        val tendenciaAlcista = closeCerrada > ema200Actual
        val tendenciaBajista = closeCerrada < ema200Actual

        val gatilloLong = (closePrevia <= ema7Previa) && (closeCerrada > ema7Actual)
        val gatilloShort = (closePrevia >= ema7Previa) && (closeCerrada < ema7Actual)

        var senal = "NEUTRAL"
        if (tendenciaAlcista && gatilloLong && adxActual >= 18 && rsiActual < 65 && macdHistAct > macdHistPrev) {
            senal = "LONG"
        } else if (tendenciaBajista && gatilloShort && adxActual >= 18 && rsiActual > 35 && macdHistAct < macdHistPrev) {
            senal = "SHORT"
        }

        if (senal == "NEUTRAL") { onLog("ESTADO: SIN SEÑAL TÉCNICA"); return }

        if (senal == "LONG" && closeCerrada >= bbUpperActual) {
            onLog("❌ RECHAZADO: EXTENUACIÓN ALCISTA (Rozando techo de Bollinger)")
            return
        }
        if (senal == "SHORT" && closeCerrada <= bbLowerActual) {
            onLog("❌ RECHAZADO: EXTENUACIÓN BAJISTA (Rozando suelo de Bollinger)")
            return
        }

        // Aquí ya estamos usando las variables, por lo que el aviso de Kotlin desaparecerá
        ejecutarValidacionFinal(symbol, senal, closeActual, atrActual, spread)
    }

    private fun ejecutarValidacionFinal(symbol: String, senal: String, closeActual: Double, atrActual: Double, spread: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                onLog("🔍 Validando Fuerza y Orderbook para $symbol...")

                // 1. Verificar Orderbook (Muros Limit)
                val obUrl = "https://fapi.binance.com/fapi/v1/depth?symbol=$symbol&limit=5"
                val obReq = client.newCall(Request.Builder().url(obUrl).build()).execute()
                val obResp = obReq.body?.string() ?: ""
                obReq.close()

                if (!obResp.startsWith("{")) {
                    onLog("⚠️ API ERROR: No se pudo leer Orderbook de $symbol (Posible bloqueo de red). Abortando.")
                    return@launch
                }

                val ob = JsonParser.parseString(obResp).asJsonObject
                var bidQty = 0.0; var askQty = 0.0
                ob.getAsJsonArray("bids").forEach { bidQty += it.asJsonArray[1].asDouble }
                ob.getAsJsonArray("asks").forEach { askQty += it.asJsonArray[1].asDouble }

                if (senal == "LONG" && askQty > (bidQty * 3.5)) { onLog("❌ ESTADO: RECHAZADO - MURO DE VENTA"); return@launch }
                if (senal == "SHORT" && bidQty > (askQty * 3.5)) { onLog("❌ ESTADO: RECHAZADO - MURO DE COMPRA"); return@launch }

                // 2. Verificar Flujo de Trades Reales
                val trUrl = "https://fapi.binance.com/fapi/v1/trades?symbol=$symbol&limit=500"
                val trReq = client.newCall(Request.Builder().url(trUrl).build()).execute()
                val trResp = trReq.body?.string() ?: ""
                trReq.close()

                if (!trResp.startsWith("[")) {
                    onLog("⚠️ API ERROR: No se pudo leer el flujo de trades de $symbol.")
                    return@launch
                }

                val trJson = JsonParser.parseString(trResp).asJsonArray
                var volTotal = 0.0; var volCompras = 0.0
                trJson.forEach {
                    val qty = it.asJsonObject.get("qty").asDouble
                    val isBuyerMaker = it.asJsonObject.get("isBuyerMaker").asBoolean
                    volTotal += qty
                    if (!isBuyerMaker) volCompras += qty
                }
                val pctCompras = if (volTotal > 0) (volCompras / volTotal) * 100 else 50.0

                if ((senal == "LONG" && pctCompras < 40.0) || (senal == "SHORT" && pctCompras > 60.0)) {
                    onLog("❌ ESTADO: RECHAZADO - FUERZA INSUFICIENTE EN TRADES (${String.format(Locale.US, "%.1f", pctCompras)}%)")
                    return@launch
                }

                // 3. Sistema de Cooldown
                val last = registroBloqueo[symbol] ?: 0L
                if (System.currentTimeMillis() - last < 300000) {
                    onLog("⏳ ESTADO: IGNORADO - COOLDOWN (Esperando ${(300000 - (System.currentTimeMillis() - last))/1000}s)")
                    return@launch
                }

                // 4. Cálculo exacto de SL y TP
                var distSl = atrActual * config.multiplicadorSl

                // Modificación: La gestión de riesgo usa ahora el valor de config
                val maxRiesgoPctBilletera = config.riesgoMaximoBilletera

                val riesgoMaximoUsdt = billeteraUsdt * (maxRiesgoPctBilletera / 100.0)
                val margenUsdt = billeteraUsdt * (config.porcentajeInversion / 100.0)
                val tamanoPosicionUsdt = margenUsdt * config.apalancamiento

                val distSlMaxPermitida = if (tamanoPosicionUsdt > 0) {
                    (riesgoMaximoUsdt / tamanoPosicionUsdt) * closeActual
                } else {
                    closeActual * 0.005
                }

                if (distSl > distSlMaxPermitida) distSl = distSlMaxPermitida

                val spreadAbsoluto = (spread / 100.0) * closeActual
                if (distSl <= spreadAbsoluto * 1.5) {
                    onLog("❌ RECHAZADO: SL ($distSl) es menor al spread crítico ($spreadAbsoluto). Riesgo matemático de cierre instantáneo.")
                    return@launch
                }

                val distTp = distSl * config.multiplicadorTp
                val tp = if (senal == "LONG") closeActual + distTp else closeActual - distTp
                val sl = if (senal == "LONG") closeActual - distSl else closeActual + distSl

                // Aprobación Final
                registroBloqueo[symbol] = System.currentTimeMillis()
                onLog("🎯 ¡OPORTUNIDAD $senal DETECTADA EN $symbol!")

                val velasEst = Math.max(1, (distTp / atrActual).toInt())

                // Pasamos el Context (ya usado) y generamos la tarjeta de alerta
                AlertManager.agregarAlerta(
                    context,
                    AlertData(symbol, senal, closeActual, tp, sl, velasEst, config.timeframe)
                )

            } catch (e: Exception) {
                onLog("❌ Error API en validación final: ${e.message}")
            }
        }
    }
}