package com.mauri.appscannertokens

import android.content.Context
import com.mauri.appscannertokens.data.remote.BinanceAccountService
import com.mauri.appscannertokens.data.remote.BinanceMarketDataService
import com.mauri.appscannertokens.data.remote.BinanceOrderService
import com.mauri.appscannertokens.data.remote.BinanceRestClient
import com.mauri.appscannertokens.data.remote.BinanceTickerWebSocket
import com.mauri.appscannertokens.data.repository.ConfigRepository
import com.mauri.appscannertokens.data.repository.PositionRepository

object AppGraph {
    private var initialized = false

    val restClient = BinanceRestClient()
    val accountService = BinanceAccountService(restClient)
    val marketDataService = BinanceMarketDataService(restClient)
    val orderService = BinanceOrderService(restClient)
    val tickerWebSocket = BinanceTickerWebSocket()

    lateinit var configRepository: ConfigRepository
        private set

    fun initialize(context: Context) {
        if (!initialized) {
            configRepository = ConfigRepository(context.applicationContext)
            AlertRepository.initialize(context.applicationContext)
            PositionRepository.initialize(context.applicationContext)
            initialized = true
        }
    }
}
