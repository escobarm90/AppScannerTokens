package com.mauri.appscannertokens
data class UserConfig(
    // Credenciales Binance
    val apiKey: String = "",
    val apiSecret: String = "",

    // Parámetros de Inversión
    val porcentajeInversion: Double = 0.30,
    val apalancamiento: Int = 20,
    val tipoMargen: String = "CROSSED",

    // Estrategia y Riesgo
    val multiplicadorSl: Double = 1.5,
    val multiplicadorTp: Double = 1.0,

    // Filtros del Scalper
    val minRatioVol: Double = 0.6,
    val minVolatilidadPct: Double = 0.25,
    val maxSpreadPct: Double = 0.15,
    val cooldownAlertaSegundos: Int = 300,
    val timeframe: String = "5m"
)
