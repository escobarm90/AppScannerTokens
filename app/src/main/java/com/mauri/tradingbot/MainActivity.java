package com.mauri.tradingbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity {

    private TextView txtAlertas;
    private Switch switchAlertas;
    private SharedPreferences prefs;

    private BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String historialActualizado = intent.getStringExtra("historial_completo");
            if (historialActualizado != null) {
                txtAlertas.setText(historialActualizado);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtAlertas = findViewById(R.id.txtAlertas);
        switchAlertas = findViewById(R.id.switchAlertas);
        prefs = getSharedPreferences("TradingPrefs", MODE_PRIVATE);

        // 1. Cargar las alertas guardadas al abrir la app
        String historialGuardado = prefs.getString("historial_alertas", "Esperando oportunidades...");
        txtAlertas.setText(historialGuardado);

        // 2. Configurar el Switch
        boolean estadoAlertas = prefs.getBoolean("recibir_alertas", true);
        switchAlertas.setChecked(estadoAlertas);

        if (estadoAlertas) {
            FirebaseMessaging.getInstance().subscribeToTopic("alertas_trading");
        } else {
            FirebaseMessaging.getInstance().unsubscribeFromTopic("alertas_trading");
        }

        switchAlertas.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("recibir_alertas", isChecked).apply();
            if (isChecked) {
                FirebaseMessaging.getInstance().subscribeToTopic("alertas_trading");
                Toast.makeText(this, "Escáner Activado ✅", Toast.LENGTH_SHORT).show();
            } else {
                FirebaseMessaging.getInstance().unsubscribeFromTopic("alertas_trading");
                Toast.makeText(this, "Escáner Apagado 💤", Toast.LENGTH_SHORT).show();
            }
        });

        // 3. Quedarse escuchando por si entra una alerta mientras tenés la pantalla encendida
        registerReceiver(messageReceiver, new IntentFilter("NUEVA_ALERTA"), Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(messageReceiver);
    }
}