package com.mauri.appscannertokens

import android.Manifest
import androidx.activity.compose.setContent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.edit

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    // Usamos estados de Compose a nivel de clase para que la UI reaccione
    private var alertasText by mutableStateOf("Esperando oportunidades...\n")
    private var debugText by mutableStateOf("Iniciando motor debug...\n")
    // Agregamos el estado del switch aquí para que sea la "fuente de verdad"
    private var motorActivo by mutableStateOf(false)

    private val receptorAlertas = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val titulo = intent.getStringExtra("titulo") ?: ""
            val cuerpo = intent.getStringExtra("cuerpo") ?: ""

            val tarjeta = """
                ╭─────────────────────────╮
                ✨ $titulo
                ├─────────────────────────┤
                $cuerpo
                ╰─────────────────────────╯
                
            """.trimIndent()

            if (alertasText.contains("Esperando oportunidades")) {
                alertasText = tarjeta
            } else {
                alertasText = tarjeta + "\n" + alertasText
            }
        }
    }

    private val receptorDebug = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val linea = intent.getStringExtra("linea_debug") ?: ""
            if (debugText.length > 8000) {
                debugText = linea + "\n" + debugText.substring(0, 6000)
            } else {
                debugText = linea + "\n" + debugText
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("TradingPrefs", MODE_PRIVATE)

        // Sincronizamos el estado inicial del motor
        motorActivo = prefs.getBoolean("recibir_alertas", false)

        // Permisos para Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Registro de receptores (Usando registerReceiver nativo o ContextCompat)
        val filterAlertas = IntentFilter("NUEVA_ALERTA")
        val filterDebug = IntentFilter("NUEVO_DEBUG")

        registerReceiver(receptorAlertas, filterAlertas, RECEIVER_NOT_EXPORTED)
        registerReceiver(receptorDebug, filterDebug, RECEIVER_NOT_EXPORTED)

        if (motorActivo) iniciarMotor()

        setContent {
            MaterialTheme {
                MainScreen(
                    alertasText = alertasText,
                    debugText = debugText,
                    motorActivo = motorActivo, // Pasamos el estado real
                    onConfigClick = {
                        startActivity(Intent(this, ConfigActivity::class.java))
                    },
                    onToggleMotor = { isChecked ->
                        motorActivo = isChecked
                        prefs.edit { putBoolean("recibir_alertas", isChecked) }

                        if (isChecked) {
                            iniciarMotor()
                            Toast.makeText(this, "Radar Binance Activado 🚀", Toast.LENGTH_SHORT).show()
                        } else {
                            detenerMotor()
                            Toast.makeText(this, "Radar Apagado 💤", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }

    private fun iniciarMotor() {
        val serviceIntent = Intent(this, TradingScannerService::class.java)
        startForegroundService(serviceIntent)
    }

    private fun detenerMotor() {
        stopService(Intent(this, TradingScannerService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receptorAlertas)
            unregisterReceiver(receptorDebug)
        } catch (e: Exception) { }
    }
}

@Composable
fun MainScreen(
    alertasText: String,
    debugText: String,
    motorActivo: Boolean, // Ahora viene de la Activity
    onConfigClick: () -> Unit,
    onToggleMotor: (Boolean) -> Unit
) {
    // Solo el modo debug es local a la pantalla
    var isDebugMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp)
    ) {
        // Cabecera
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(
                text = "📡 SCANNER DE ALERTAS",
                color = Color(0xFF2EA043),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
            IconButton(onClick = onConfigClick, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Default.Settings, "Config", tint = Color(0xFF8B949E))
            }
        }

        // Switches de control
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 15.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alertas", color = Color.White)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = motorActivo,
                    onCheckedChange = { onToggleMotor(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF2EA043))
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Modo Debug", color = Color(0xFF8B949E), fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Switch(checked = isDebugMode, onCheckedChange = { isDebugMode = it })
            }
        }

        HorizontalDivider(color = Color(0xFF30363D))

        // Contenedor de Texto (Scrollable)
        val scrollState = rememberScrollState()

        Box(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .background(if (isDebugMode) Color.Black else Color.Transparent)
                    .padding(8.dp)
            ) {
                Text(
                    text = if (isDebugMode) debugText else alertasText,
                    color = if (isDebugMode) Color(0xFF00FF00) else Color(0xFF8B949E),
                    fontSize = if (isDebugMode) 12.sp else 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}