package com.mauri.appscannertokens.di

import android.content.Context
import com.mauri.appscannertokens.data.repository.ConfigRepository
import com.mauri.appscannertokens.data.remote.*

object AppGraph {
    lateinit var configRepository: ConfigRepository

    // Inicializamos las dependencias en orden
    val restClient by lazy { BinanceRestClient() }
    val accountService by lazy { BinanceAccountService(restClient) }
    val marketDataService by lazy { BinanceMarketDataService(restClient) }
    val orderService by lazy { BinanceOrderService(restClient) }
    val tickerWebSocket by lazy { BinanceTickerWebSocket() }

    fun initialize(context: Context) {
        configRepository = ConfigRepository(context)
    }
}