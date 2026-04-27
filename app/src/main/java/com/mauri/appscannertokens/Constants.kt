package com.mauri.appscannertokens

object PrefKeys {
    const val PREFS_NAME = "TradingPrefs"
    const val API_KEY = "api_key"
    const val SECRET_KEY = "secret_key"
    const val TIMEFRAME = "timeframe"
    const val BILLETERA_MANUAL = "billetera_manual"
    const val ROI_MINIMO = "roi_minimo"
    const val APALANCAMIENTO = "apalancamiento"

    // CAMPOS DE RIESGO
    const val TAMANO_POS_PCT = "tamano_pos"
    const val PERDIDA_MAX_PCT = "perdida_max"
    const val TIPO_MARGEN = "tipo_margen"

    // 👇 NUEVOS CAMPOS AÑADIDOS PARA PARAMETRIZACIÓN 👇
    const val SPREAD_MAXIMO = "spread_maximo"
    const val ATR_MINIMO = "atr_minimo"
    const val VOLUMEN_RATIO_MINIMO = "volumen_ratio_minimo"
    const val RIESGO_MAXIMO_BILLETERA = "riesgo_maximo_billetera"
}

object RiskLimits {
    const val MAX_WALLET_RISK_PCT = 2.0
}
