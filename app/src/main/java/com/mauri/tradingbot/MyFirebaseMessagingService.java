package com.mauri.tradingbot;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "AlertasTrading";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        // Leemos el modo DATA que manda Python
        if (remoteMessage.getData().size() > 0) {
            String titulo = remoteMessage.getData().get("titulo");
            String cuerpo = remoteMessage.getData().get("cuerpo");

            if (titulo == null) titulo = "🚨 Nueva Alerta de Trading";
            if (cuerpo == null) cuerpo = "Revisá los gráficos.";

            mostrarNotificacion(titulo, cuerpo);
            guardarYEnviarAPantalla(titulo, cuerpo);
        }
    }

    private void mostrarNotificacion(String titulo, String cuerpo) {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alertas de Trading",
                    NotificationManager.IMPORTANCE_HIGH
            );
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(titulo)
                .setContentText(cuerpo)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(cuerpo))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVibrate(new long[]{1000, 1000, 1000, 1000}) // Vibración fuerte
                .setAutoCancel(true);

        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void guardarYEnviarAPantalla(String titulo, String cuerpo) {
        SharedPreferences prefs = getSharedPreferences("TradingPrefs", MODE_PRIVATE);
        String historialActual = prefs.getString("historial_alertas", "");
        if (historialActual.contains("Esperando")) historialActual = "";

        String nuevaAlerta = "--------------------------\n" +
                "🚀 " + titulo + "\n" +
                cuerpo + "\n\n";

        String nuevoHistorial = nuevaAlerta + historialActual;

        // Limite de memoria (aprox 50 alertas) para no saturar la app
        if (nuevoHistorial.length() > 15000) {
            nuevoHistorial = nuevoHistorial.substring(0, 15000);
        }

        prefs.edit().putString("historial_alertas", nuevoHistorial).apply();

        Intent intent = new Intent("NUEVA_ALERTA");
        intent.putExtra("historial_completo", nuevoHistorial);
        sendBroadcast(intent);
    }
}