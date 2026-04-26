package com.mauri.appscannertokens

data class UserConfig(
    var apiKey: String = "",
    var apiSecret: String = "",
    var timeframe: String = "5m",
    var billeteraManual: Double = 20.0,
    var roiMinimo: Double = 1.0,
    var apalancamiento: Int = 20,
    var porcentajeInversion: Double = 30.0,
    var multiplicadorSl: Double = 1.5,
    var multiplicadorTp: Double = 1.0,

    // 👇 EL CAMPO QUE FALTABA PARA POSITION MANAGER 👇
    var tipoMargen: String = "ISOLATED",

    // Nuevos parámetros dinámicos de los filtros
    var spreadMaximo: Double = 0.10,
    var atrMinimo: Double = 0.50,
    var volumenRatioMinimo: Double = 1.20,
    var riesgoMaximoBilletera: Double = 2.0
)