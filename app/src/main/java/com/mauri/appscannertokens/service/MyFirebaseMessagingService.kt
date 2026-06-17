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
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "AlertasTrading"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Leemos el modo DATA que manda Python
        if (remoteMessage.data.isNotEmpty()) {
            val titulo = remoteMessage.data["titulo"] ?: "🚨 Nueva Alerta de Trading"
            val cuerpo = remoteMessage.data["cuerpo"] ?: "Revisá los gráficos."

            mostrarNotificacion(titulo, cuerpo)
            guardarYEnviarAPantalla(titulo, cuerpo)
        }
    }

    private fun mostrarNotificacion(titulo: String, cuerpo: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alertas de Trading",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cuerpo))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(longArrayOf(1000, 1000, 1000, 1000)) // Vibración fuerte
            .setAutoCancel(true)

        notificationManager?.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun guardarYEnviarAPantalla(titulo: String, cuerpo: String) {
        val prefs = getSharedPreferences("TradingPrefs", Context.MODE_PRIVATE)
        var historialActual = prefs.getString("historial_alertas", "") ?: ""

        if (historialActual.contains("Esperando")) {
            historialActual = ""
        }

        val nuevaAlerta = """
            --------------------------
            🚀 $titulo
            $cuerpo
            
            
        """.trimIndent()

        var nuevoHistorial = nuevaAlerta + historialActual

        // Limite de memoria (aprox 50 alertas) para no saturar la app
        if (nuevoHistorial.length > 15000) {
            nuevoHistorial = nuevoHistorial.substring(0, 15000)
        }

        // Guardado usando la extensión KTX de Kotlin
        prefs.edit {
            putString("historial_alertas", nuevoHistorial)
        }

        val intent = Intent("NUEVA_ALERTA")
        intent.setPackage(packageName) // Etiqueta de seguridad para el broadcast
        intent.putExtra("historial_completo", nuevoHistorial)
        sendBroadcast(intent)
    }
}