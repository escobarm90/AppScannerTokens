package com.mauri.appscannertokens

import android.Manifest
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
import androidx.activity.compose.setContent
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
import androidx.core.content.edit

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    private var alertasText by mutableStateOf("Esperando oportunidades...\n")
    private var debugText by mutableStateOf("Iniciando motor debug...\n")
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

            alertasText = if (alertasText.contains("Esperando oportunidades")) tarjeta else "$tarjeta\n$alertasText"
        }
    }

    private val receptorDebug = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val linea = intent.getStringExtra("linea_debug") ?: ""
            debugText = "$linea\n${if (debugText.length > 6000) debugText.substring(0, 6000) else debugText}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. RENDERIZAMOS LA UI PRIMERO (Evita pantalla en blanco si hay crasheo)
        setContent {
            MaterialTheme {
                MainScreen(
                    alertasText = alertasText,
                    debugText = debugText,
                    motorActivo = motorActivo,
                    onConfigClick = {
                        startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                    },
                    onToggleMotor = { isChecked ->
                        motorActivo = isChecked
                        prefs.edit { putBoolean("recibir_alertas", isChecked) }

                        if (isChecked) {
                            iniciarMotor()
                            Toast.makeText(this@MainActivity, "Radar Binance Activado 🚀", Toast.LENGTH_SHORT).show()
                        } else {
                            detenerMotor()
                            Toast.makeText(this@MainActivity, "Radar Apagado 💤", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        // 2. LÓGICA DE PREFERENCIAS Y PERMISOS DESPUÉS DEL RENDER
        prefs = getSharedPreferences("TradingPrefs", MODE_PRIVATE)
        motorActivo = prefs.getBoolean("recibir_alertas", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        try {
            val filterAlertas = IntentFilter("NUEVA_ALERTA")
            val filterDebug = IntentFilter("NUEVO_DEBUG")
            ContextCompat.registerReceiver(this, receptorAlertas, filterAlertas, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, receptorDebug, filterDebug, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            debugText = "⚠️ Error registrando receivers: ${e.message}\n$debugText"
        }

        // Si estaba encendido en la sesión anterior, lo prendemos con cuidado
        if (motorActivo) iniciarMotor()
    }

    private fun iniciarMotor() {
        try {
            val serviceIntent = Intent(this, TradingScannerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            debugText = "🚨 CRASH PREVENIDO AL INICIAR SERVICIO: ${e.message}\n$debugText"
            motorActivo = false
            prefs.edit { putBoolean("recibir_alertas", false) }
        }
    }

    private fun detenerMotor() {
        try {
            stopService(Intent(this, TradingScannerService::class.java))
        } catch (e: Exception) {
            debugText = "⚠️ Error deteniendo servicio: ${e.message}\n$debugText"
        }
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
    motorActivo: Boolean,
    onConfigClick: () -> Unit,
    onToggleMotor: (Boolean) -> Unit
) {
    var isDebugMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp)
    ) {
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