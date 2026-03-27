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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.util.Locale

data class AlertaData(
    val token: String,
    val senal: String,
    val hora: String,
    val pnlInfo: String,
    val detalles: String
)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    private var alertas = mutableStateListOf<AlertaData>()
    private var debugText by mutableStateOf("Iniciando motor debug...\n")
    private var motorActivo by mutableStateOf(false)
    private var saldoBilletera by mutableStateOf(0.0)

    private val receptorAlertas = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val token = intent.getStringExtra("token") ?: ""
            val senal = intent.getStringExtra("senal") ?: ""
            val hora = intent.getStringExtra("hora") ?: ""
            val pnlInfo = intent.getStringExtra("pnl_info") ?: ""
            val cuerpo = intent.getStringExtra("cuerpo") ?: ""

            alertas.add(0, AlertaData(token, senal, hora, pnlInfo, cuerpo))
        }
    }

    private val receptorDebug = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val linea = intent.getStringExtra("linea_debug") ?: ""
            debugText = "$linea\n${if (debugText.length > 6000) debugText.substring(0, 6000) else debugText}"
        }
    }

    private val receptorSaldo = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            saldoBilletera = intent.getDoubleExtra("saldo", 0.0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)
        motorActivo = prefs.getBoolean("recibir_alertas", false)
        saldoBilletera = prefs.getFloat(PrefKeys.BILLETERA_MANUAL, 0f).toDouble()

        setContent {
            MaterialTheme {
                MainScreen(
                    alertas = alertas,
                    debugText = debugText,
                    motorActivo = motorActivo,
                    saldoBilletera = saldoBilletera,
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        try {
            val filterAlertas = IntentFilter("NUEVA_ALERTA")
            val filterDebug = IntentFilter("NUEVO_DEBUG")
            val filterSaldo = IntentFilter("NUEVO_SALDO")

            ContextCompat.registerReceiver(this, receptorAlertas, filterAlertas, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, receptorDebug, filterDebug, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this, receptorSaldo, filterSaldo, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            debugText = "⚠️ Error registrando receivers: ${e.message}\n$debugText"
        }

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
            motorActivo = false
            prefs.edit { putBoolean("recibir_alertas", false) }
        }
    }

    private fun detenerMotor() {
        try {
            stopService(Intent(this, TradingScannerService::class.java))
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receptorAlertas)
            unregisterReceiver(receptorDebug)
            unregisterReceiver(receptorSaldo)
        } catch (e: Exception) { }
    }
}

@Composable
fun MainScreen(
    alertas: List<AlertaData>,
    debugText: String,
    motorActivo: Boolean,
    saldoBilletera: Double,
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
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Text(
                    text = "📡 SCANNER DE ALERTAS",
                    color = Color(0xFF2EA043),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Saldo Billetera: $${String.format(Locale.US, "%.2f", saldoBilletera)} USDT",
                    color = Color(0xFFE3B341),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

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
                Text("Motor", color = Color.White)
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

        Box(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
            if (isDebugMode) {
                val scrollState = rememberScrollState()
                Text(
                    text = debugText,
                    color = Color(0xFF00FF00),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState).background(Color.Black).padding(8.dp)
                )
            } else {
                if (alertas.isEmpty()) {
                    Text(
                        text = "Esperando oportunidades...",
                        color = Color(0xFF8B949E),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(alertas) { alerta ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = alerta.token,
                                            color = Color.White,
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            text = alerta.hora,
                                            color = Color(0xFF8B949E),
                                            fontSize = 14.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = "${if (alerta.senal == "LONG") "🟢" else "🔴"} ${alerta.senal}  |  ${alerta.pnlInfo}",
                                        color = if (alerta.senal == "LONG") Color(0xFF2EA043) else Color(0xFFF85149),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    HorizontalDivider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 8.dp))

                                    Text(
                                        text = alerta.detalles,
                                        color = Color(0xFFC9D1D9),
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}