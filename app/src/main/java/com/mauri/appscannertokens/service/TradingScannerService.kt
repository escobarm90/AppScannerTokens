package com.mauri.appscannertokens.service

import com.mauri.appscannertokens.domain.model.*
import com.mauri.appscannertokens.data.repository.*
import com.mauri.appscannertokens.data.remote.*
import com.mauri.appscannertokens.presentation.ui.*
import com.mauri.appscannertokens.presentation.viewmodel.*
import com.mauri.appscannertokens.presentation.notifier.*
import com.mauri.appscannertokens.engine.*
import com.mauri.appscannertokens.service.*


import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mauri.appscannertokens.di.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TradingScannerService : Service() {
    companion object {
        const val CHANNEL_ID = "TradingScannerChannel"
        const val NOTIFICATION_ID = 1
        var isRunning = false
        var isDebugMode = true
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var scannerJob: Job? = null
    private var scannerEngine: ScannerEngine? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (scannerJob?.isActive == true) {
            LogRepository.add("Scanner ya estaba activo; se evita duplicar el loop.")
            return START_STICKY
        }

        isRunning = true
        val engine = ScannerEngine(
            context = applicationContext,
            configRepository = AppGraph.configRepository,
            accountService = AppGraph.accountService,
            marketDataService = AppGraph.marketDataService,
            tickerWebSocket = AppGraph.tickerWebSocket,
            klineWebSocket = BinanceKlineWebSocket(),
            strategyAnalyzer = StrategyAnalyzer(),
            signalValidator = SignalValidator(AppGraph.marketDataService),
            riskCalculator = RiskCalculator(),
            alertRepository = AlertRepository,
            logRepository = LogRepository
        )
        scannerEngine = engine

        scannerJob = serviceScope.launch {
            try {
                engine.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogRepository.add("Error fatal en scanner: ${e.message}")
            } finally {
                engine.stop()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scannerEngine?.stop()
        scannerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Motor Scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Scanner Activo")
        .setContentText("Monitoreando mercados en tiempo real...")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()
}
