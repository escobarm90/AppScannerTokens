package com.mauri.appscannertokens

import kotlin.math.abs

class TrailingStopEngine {
    fun calculateUpdate(
        entryPrice: Double,
        currentPrice: Double,
        currentTp: Double,
        isLong: Boolean,
        currentLevel: Int,
        lastUpdateAt: Long,
        now: Long = System.currentTimeMillis()
    ): TrailingUpdate? {
        if (now - lastUpdateAt < MIN_UPDATE_INTERVAL_MS) return null

        val distance = abs(currentTp - entryPrice)
        if (distance <= 0.0) return null

        val progress = if (isLong) {
            (currentPrice - entryPrice) / distance
        } else {
            (entryPrice - currentPrice) / distance
        }

        if (progress < 0.70) return null

        val newSl = if (isLong) entryPrice + (distance * 0.50) else entryPrice - (distance * 0.50)
        val newTp = if (isLong) currentTp + (distance * 0.30) else currentTp - (distance * 0.30)

        return TrailingUpdate(
            newTp = newTp,
            newSl = newSl,
            newLevel = currentLevel + 1
        )
    }

    private companion object {
        const val MIN_UPDATE_INTERVAL_MS = 10_000L
    }
}
