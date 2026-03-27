package com.mauri.appscannertokens;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView txtAlertas, txtDebug;
    private ScrollView scrollAlertas, scrollDebug;
    private Switch switchAlertas, switchDebug;
    private ImageButton btnConfiguracion;
    private SharedPreferences prefs;

    // 📻 RADIO 1: Escucha las alertas de plata (LONG/SHORT)
    private final BroadcastReceiver receptorAlertas = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String titulo = intent.getStringExtra("titulo");
            String cuerpo = intent.getStringExtra("cuerpo");

            String tarjeta = "╭─────────────────────────╮\n" +
                    "✨ " + titulo + "\n" +
                    "├─────────────────────────┤\n" +
                    cuerpo + "\n" +
                    "╰─────────────────────────╯\n\n";

            String actual = txtAlertas.getText().toString();
            if (actual.contains("Esperando oportunidades")) actual = "";
            txtAlertas.setText(tarjeta + actual);
        }
    };

    // 📻 RADIO 2: Escucha la matrix del código (Modo Debug)
    private final BroadcastReceiver receptorDebug = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String linea = intent.getStringExtra("linea_debug");
            String actual = txtDebug.getText().toString();

            if (actual.length() > 8000) {
                actual = actual.substring(0, 6000);
            }
            txtDebug.setText(linea + "\n" + actual);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtAlertas = findViewById(R.id.txtAlertas);
        txtDebug = findViewById(R.id.txtDebug);
        scrollAlertas = findViewById(R.id.scrollAlertas);
        scrollDebug = findViewById(R.id.scrollDebug);
        switchAlertas = findViewById(R.id.switchAlertas);
        switchDebug = findViewById(R.id.switchDebug);
        btnConfiguracion = findViewById(R.id.btnConfiguracion);

        prefs = getSharedPreferences("TradingPrefs", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        switchDebug.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                scrollAlertas.setVisibility(View.GONE);
                scrollDebug.setVisibility(View.VISIBLE);
            } else {
                scrollAlertas.setVisibility(View.VISIBLE);
                scrollDebug.setVisibility(View.GONE);
            }
        });

        boolean estadoAlertas = prefs.getBoolean("recibir_alertas", false);
        switchAlertas.setChecked(estadoAlertas);

        if (estadoAlertas) {
            iniciarMotor();
        }

        switchAlertas.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("recibir_alertas", isChecked).apply();
            if (isChecked) {
                iniciarMotor();
                Toast.makeText(this, "Radar Binance Activado 🚀", Toast.LENGTH_SHORT).show();
            } else {
                detenerMotor();
                Toast.makeText(this, "Radar Apagado 💤", Toast.LENGTH_SHORT).show();
            }
        });

        btnConfiguracion.setOnClickListener(v ->
                Toast.makeText(this, "Próximamente: Ajustes", Toast.LENGTH_SHORT).show()
        );

        // 🔥 CORRECCIÓN ANTIBALAS PARA LOS RECEIVERS 🔥
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, receptorAlertas, new IntentFilter("NUEVA_ALERTA"), ContextCompat.RECEIVER_NOT_EXPORTED);
            ContextCompat.registerReceiver(this, receptorDebug, new IntentFilter("NUEVO_DEBUG"), ContextCompat.RECEIVER_NOT_EXPORTED);
        } else {
            ContextCompat.registerReceiver(this, receptorAlertas, new IntentFilter("NUEVA_ALERTA"), ContextCompat.RECEIVER_NOT_EXPORTED);
            ContextCompat.registerReceiver(this, receptorDebug, new IntentFilter("NUEVO_DEBUG"), ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }

    // Métodos de control del servicio nativo
    private void iniciarMotor() {
        Intent serviceIntent = new Intent(this, TradingScannerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void detenerMotor() {
        Intent serviceIntent = new Intent(this, TradingScannerService.class);
        stopService(serviceIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(receptorAlertas);
            unregisterReceiver(receptorDebug);
        } catch (Exception e) {
            // Ignorar si ya estaban desregistrados
        }
    }
}