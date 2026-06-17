package com.mauri.appscannertokens.domain.model

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


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