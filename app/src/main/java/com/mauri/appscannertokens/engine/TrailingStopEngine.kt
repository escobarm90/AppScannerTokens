package com.mauri.appscannertokens.engine

import com.mauri.appscannertokens.domain.model.ActivePosition
import kotlin.math.abs

class TrailingStopEngine {

    // Devuelve el nuevo precio exacto para el Stop Loss en Binance, o null si aún no hay que moverlo.
    fun calculateNewStopLoss(position: ActivePosition, currentPrice: Double): Double? {
        val isLong = position.side == "LONG"
        val totalDistance = abs(position.initialTp - position.entryPrice)

        if (totalDistance <= 0) return null

        val profitDistance = if (isLong) {
            currentPrice - position.entryPrice
        } else {
            position.entryPrice - currentPrice
        }

        // ¿Qué porcentaje de nuestra ganancia estimada hemos alcanzado?
        val profitPctOfExpected = profitDistance / totalDistance

        // Si aún no hemos llegado al 60% de la ganancia estimada, no hacemos nada.
        if (profitPctOfExpected < 0.60) return null

        val currentLevel = position.trailingLevel ?: 0

        // NIVEL 1: Alcanzó el 60% de la ganancia.
        // Acción: SL = Precio Alcanzado - 20% de la ganancia estimada total.
        if (profitPctOfExpected >= 0.60 && currentLevel < 1) {
            val discount = totalDistance * 0.20
            return if (isLong) currentPrice - discount else currentPrice + discount
        }

        // NIVEL 2: Alcanzó el 90% (Subió otro 30%).
        // Acción: Mueve el SL de nuevo asegurando casi todo.
        if (profitPctOfExpected >= 0.90 && currentLevel < 2) {
            val discount = totalDistance * 0.20
            return if (isLong) currentPrice - discount else currentPrice + discount
        }

        return null
    }

    fun getNewLevel(position: ActivePosition, currentPrice: Double): Int {
        val totalDistance = abs(position.initialTp - position.entryPrice)
        if (totalDistance <= 0) return position.trailingLevel ?: 0

        val isLong = position.side == "LONG"
        val profitDistance = if (isLong) currentPrice - position.entryPrice else position.entryPrice - currentPrice
        val profitPctOfExpected = profitDistance / totalDistance

        return when {
            profitPctOfExpected >= 0.90 -> 2
            profitPctOfExpected >= 0.60 -> 1
            else -> position.trailingLevel ?: 0
        }
    }
}