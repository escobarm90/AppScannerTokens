package com.mauri.appscannertokens

data class UserConfig(
    // Credenciales vacías por defecto
    val apiKey: String = "",
    val apiSecret: String = "",

    // Temporalidad por defecto del escáner
    val timeframe: String = "5m",

    // ==========================================
    // VALORES POR DEFECTO: GESTIÓN DE RIESGO
    // ==========================================
    val apalancamiento: Int = 10,                 // Apalancamiento 10x
    val porcentajeInversion: Double = 10.0,       // 10% de la billetera por trade
    val multiplicadorTp: Double = 1.50,           // Multiplicador TP 1.50
    val multiplicadorSl: Double = 1.0,          // Multiplicador SL 1.0
    var tipoMargen: String = "CROSSED",

    // ==========================================
    // VALORES POR DEFECTO: FILTROS TÉCNICOS
    // ==========================================
    val minRatioVol: Double = 1.20,               // Mínimo ratio volumen 1.20
    val minVolatilidadPct: Double = 0.50,         // Volatilidad ATR al 0.50%
    val maxSpreadPct: Double = 0.10,              // Spread máximo al 0.10%

    // ==========================================
    // SISTEMA
    // ==========================================
    val cooldownAlertaSegundos: Long = 300        // 5 minutos de enfriamiento por moneda
)
