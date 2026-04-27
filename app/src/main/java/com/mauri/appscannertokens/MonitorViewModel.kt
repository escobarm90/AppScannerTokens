package com.mauri.appscannertokens

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    val alerts: StateFlow<List<AlertData>> = AlertRepository.alerts
    val logs: StateFlow<List<String>> = LogRepository.logs
    val positions: StateFlow<List<ActivePosition>> = PositionRepository.positions

    private val configState = MutableStateFlow(UserConfig())
    val config: StateFlow<UserConfig> = configState.asStateFlow()

    private val balanceState = MutableStateFlow(0.0)
    val balance: StateFlow<Double> = balanceState.asStateFlow()

    private val loadingBalanceState = MutableStateFlow(false)
    val isLoadingBalance: StateFlow<Boolean> = loadingBalanceState.asStateFlow()

    private val motorRunningState = MutableStateFlow(TradingScannerService.isRunning)
    val isMotorRunning: StateFlow<Boolean> = motorRunningState.asStateFlow()

    private val debugState = MutableStateFlow(TradingScannerService.isDebugMode)
    val isDebugEnabled: StateFlow<Boolean> = debugState.asStateFlow()

    init {
        AppGraph.initialize(application)
        AppGraph.tickerWebSocket.start()
        PositionManager.initialize(application)
        reloadConfig()
        refreshBalance()
    }

    fun reloadConfig() {
        configState.value = AppGraph.configRepository.load()
    }

    fun refreshBalance() {
        val currentConfig = configState.value
        if (currentConfig.apiKey.isBlank() || currentConfig.apiSecret.isBlank()) return

        viewModelScope.launch {
            loadingBalanceState.value = true
            balanceState.value = AppGraph.accountService.getAvailableUsdtBalance(
                currentConfig.apiKey,
                currentConfig.apiSecret
            )
            loadingBalanceState.value = false
        }
    }

    fun setMotorRunning(isRunning: Boolean) {
        val app = getApplication<Application>()
        motorRunningState.value = isRunning
        TradingScannerService.isRunning = isRunning

        val intent = Intent(app, TradingScannerService::class.java)
        if (isRunning) {
            ContextCompat.startForegroundService(app, intent)
        } else {
            app.stopService(intent)
        }
    }

    fun setDebugEnabled(isEnabled: Boolean) {
        debugState.value = isEnabled
        TradingScannerService.isDebugMode = isEnabled
    }

    suspend fun executeLimit(request: AlertExecutionRequest): String =
        executeAlert(request, orderType = "LIMIT")

    suspend fun executeMarket(request: AlertExecutionRequest): String =
        executeAlert(request, orderType = "MARKET")

    fun removeAlert(alert: AlertData) {
        AlertRepository.remove(alert)
    }

    fun closePosition(symbol: String) {
        PositionManager.closePositionManual(getApplication(), configState.value, symbol)
    }

    private suspend fun executeAlert(request: AlertExecutionRequest, orderType: String): String {
        val currentConfig = configState.value.copy(tipoMargen = request.marginType)
        val sizing = OrderSizingCalculator.calculate(
            alert = request.alert,
            config = currentConfig,
            walletUsdt = balanceState.value,
            walletPercent = request.walletPercent
        )

        if (sizing.quantity <= 0.0) return "No se pudo calcular cantidad para la orden"

        val side = if (request.alert.senal == "LONG") "BUY" else "SELL"
        val result = AppGraph.orderService.executeOrder(
            apiKey = currentConfig.apiKey,
            apiSecret = currentConfig.apiSecret,
            symbol = request.alert.symbol,
            side = side,
            orderType = orderType,
            quantity = sizing.quantity,
            price = request.alert.precio,
            marginType = request.marginType
        )

        if (result.success && result.orderId > 0L) {
            LogRepository.add("Orden $orderType enviada: ${request.alert.symbol} | ID ${result.orderId}")
            PositionManager.startMonitoring(
                context = getApplication(),
                config = currentConfig,
                symbol = request.alert.symbol,
                signal = request.alert.senal,
                entryPrice = request.alert.precio,
                takeProfit = request.alert.tp,
                stopLoss = request.alert.sl,
                leverage = currentConfig.apalancamiento,
                orderId = result.orderId,
                quantity = sizing.quantity,
                atr = request.alert.atr
            )
        } else {
            LogRepository.add(result.message)
        }

        return result.message
    }
}
