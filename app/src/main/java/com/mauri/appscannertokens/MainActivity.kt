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
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Locale

// --- NUEVA DATA CLASS ESTRUCTURADA ---
data class AlertaDetallada(
    val tokenName: String,
    val direction: String, // "LONG" o "SHORT"
    val entryPrice: Double,
    val targetPrice: Double,
    val stopLossPrice: Double,
    val marginUsdt: Double,
    val estimatedRoiPct: Double,
    val timestamp: Long
)

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    // Lista reactiva para las tarjetas de alertas detalladas
    private val alertasList = mutableStateListOf<AlertaDetallada>()
    private var debugText by mutableStateOf("Iniciando motor debug...\n")
    private var saldoBilletera by mutableStateOf("Calculando...")

    // --- ACTUALIZACIÓN DEL RECEPTOR PARA LEER DATOS ESTRUCTURADOS ---
    private val receptorAlertas = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val token = intent.getStringExtra("token") ?: ""
            val direction = intent.getStringExtra("direction") ?: ""
            val entry = intent.getDoubleExtra("entry", 0.0)
            val tp = intent.getDoubleExtra("tp", 0.0)
            val sl = intent.getDoubleExtra("sl", 0.0)
            val margin = intent.getDoubleExtra("margin", 0.0)
            val roi = intent.getDoubleExtra("roi", 0.0)

            if (token.isNotEmpty()) {
                // Creamos el objeto de alerta detallada
                val nuevaAlerta = AlertaDetallada(
                    tokenName = token,
                    direction = direction.uppercase(),
                    entryPrice = entry,
                    targetPrice = tp,
                    stopLossPrice = sl,
                    marginUsdt = margin,
                    estimatedRoiPct = roi,
                    timestamp = System.currentTimeMillis()
                )

                // Agregamos la alerta al principio de la lista
                alertasList.add(0, nuevaAlerta)

                // Mantenemos solo las últimas 50 alertas
                if (alertasList.size > 50) {
                    alertasList.removeAt(alertasList.size - 1)
                }
            }
        }
    }

    private val receptorDebug = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val linea = intent.getStringExtra("linea_debug") ?: ""
            var actual = debugText

            if (actual.length > 8000) {
                actual = actual.substring(0, 6000)
            }
            debugText = linea + "\n" + actual
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        ContextCompat.registerReceiver(this, receptorAlertas, IntentFilter("NUEVA_ALERTA_DETALLADA"), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, receptorDebug, IntentFilter("NUEVO_DEBUG"), ContextCompat.RECEIVER_NOT_EXPORTED)

        val estadoAlertas = prefs.getBoolean("recibir_alertas", false)
        if (estadoAlertas) {
            iniciarMotor()
        }

        setContent {
            MaterialTheme {
                MainScreen(
                    alertasList = alertasList,
                    debugText = debugText,
                    saldoBilletera = saldoBilletera,
                    initialAlertasState = estadoAlertas,
                    onConfigClick = {
                        startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
                    },
                    onToggleMotor = { isChecked ->
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

            LaunchedEffect(Unit) {
                while (isActive) {
                    actualizarSaldoEnVivo()
                    delay(10000) // 10 segundos
                }
            }
        }
    }

    private suspend fun actualizarSaldoEnVivo() {
        val apiKey = prefs.getString(PrefKeys.API_KEY, "") ?: ""
        val secretKey = prefs.getString(PrefKeys.SECRET_KEY, "") ?: ""

        if (apiKey.isBlank() || secretKey.isBlank()) {
            saldoBilletera = "Sin API Keys"
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val timestamp = System.currentTimeMillis()
                val queryString = "timestamp=$timestamp"

                val sha256HMAC = Mac.getInstance("HmacSHA256")
                val secretKeySpec = SecretKeySpec(secretKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
                sha256HMAC.init(secretKeySpec)
                val hash = sha256HMAC.doFinal(queryString.toByteArray(StandardCharsets.UTF_8))
                val signature = hash.joinToString("") { "%02x".format(it) }

                val url = "https://fapi.binance.com/fapi/v2/balance?$queryString&signature=$signature"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-MBX-APIKEY", apiKey)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        val balances = JsonParser.parseString(response.body!!.string()).asJsonArray
                        for (b in balances) {
                            val balance = b.asJsonObject
                            if (balance.get("asset").asString == "USDT") {
                                val saldoReal = balance.get("balance").asDouble
                                saldoBilletera = "$${String.format(Locale.US, "%.2f", saldoReal)} USDT"
                                return@withContext
                            }
                        }
                    } else {
                        saldoBilletera = "Error API"
                    }
                }
            } catch (e: Exception) {
                saldoBilletera = "Conectando..."
            }
        }
    }

    private fun iniciarMotor() {
        val serviceIntent = Intent(this, TradingScannerService::class.java)
        startForegroundService(serviceIntent)
    }

    private fun detenerMotor() {
        val serviceIntent = Intent(this, TradingScannerService::class.java)
        stopService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receptorAlertas)
            unregisterReceiver(receptorDebug)
        } catch (_: Exception) {}
    }
}

@Composable
fun MainScreen(
    alertasList: List<AlertaDetallada>,
    debugText: String,
    saldoBilletera: String,
    initialAlertasState: Boolean,
    onConfigClick: () -> Unit,
    onToggleMotor: (Boolean) -> Unit
) {
    var isAlertasEnabled by remember { mutableStateOf(initialAlertasState) }
    var isDebugMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 15.dp)
        ) {
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Text(
                    text = "📡 RADAR TOKENS",
                    color = Color(0xFF2EA043),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Billetera: $saldoBilletera",
                    color = Color(0xFF58A6FF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            IconButton(
                onClick = onConfigClick,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configuración",
                    tint = Color(0xFF8B949E)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alertas", color = Color.White, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isAlertasEnabled,
                    onCheckedChange = {
                        isAlertasEnabled = it
                        onToggleMotor(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF2EA043),
                        checkedTrackColor = Color(0xFF238636)
                    )
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debug", color = Color(0xFF8B949E), fontSize = 14.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = isDebugMode, onCheckedChange = { isDebugMode = it })
            }
        }

        HorizontalDivider(thickness = 1.dp, color = Color(0xFF30363D))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 10.dp)
        ) {
            if (isDebugMode) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    Text(
                        text = debugText,
                        color = Color(0xFF00FF00),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                if (alertasList.isEmpty()) {
                    Text(
                        text = "Esperando oportunidades...\nEl motor está escaneando.",
                        color = Color(0xFF8B949E),
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(alertasList) { alerta ->
                            // --- NUEVA TARJETA DE ALERTA REDEFINIDA ---
                            AlertaCard(alerta = alerta)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlertaCard(alerta: AlertaDetallada) {
    val isLong = alerta.direction == "LONG"
    val colorDireccion = if (isLong) Color(0xFF3FB950) else Color(0xFFF85149)
    val colorTextoPrincipal = Color(0xFFC9D1D9)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- CABECERA GIGANTE: TOKEN Y DIRECCIÓN ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = alerta.tokenName,
                    color = Color.White,
                    fontSize = 26.sp, // Gigante
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = alerta.direction,
                    color = colorDireccion,
                    fontSize = 26.sp, // Gigante
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 1.dp, color = Color(0xFF30363D))
            Spacer(modifier = Modifier.height(10.dp))

            // --- DETALLES DE PRECIO Y MARGEN ---
            Column(modifier = Modifier.fillMaxWidth()) {
                AlertaDatoItem(icono = "⚡", label = "Apalancamiento", valor = "20x (ISOLATED)", colorTextoPrincipal)
                AlertaDatoItem(icono = "💵", label = "Entrada", valor = String.format(Locale.US, "%.4f", alerta.entryPrice), colorTextoPrincipal)
                AlertaDatoItem(icono = "✅", label = "TP", valor = String.format(Locale.US, "%.4f", alerta.targetPrice), colorTextoPrincipal)
                AlertaDatoItem(icono = "❌", label = "SL", valor = String.format(Locale.US, "%.4f", alerta.stopLossPrice), colorTextoPrincipal)
                AlertaDatoItem(icono = "💰", label = "Margen", valor = String.format(Locale.US, "$%.2f USDT", alerta.marginUsdt), colorTextoPrincipal)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, color = Color(0xFF30363D))
            Spacer(modifier = Modifier.height(12.dp))

            // --- RESULTADOS ESTIMADOS: PNL Y ROI ---
            // Calculamos el PNL: Margen * (ROI / 100)
            val pnlEstimado = alerta.marginUsdt * (alerta.estimatedRoiPct / 100.0)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ROI Estimado
                Column(horizontalAlignment = Alignment.Start) {
                    Text(text = "ROI EST:", color = colorTextoPrincipal, fontSize = 14.sp)
                    Text(
                        text = String.format(Locale.US, "%.2f%%", alerta.estimatedRoiPct),
                        color = colorDireccion, // Mismo color que la dirección (verde o rojo)
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // PNL ESTIMADO (Lo más importante)
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "PNL EST:", color = colorTextoPrincipal, fontSize = 14.sp)
                    Text(
                        text = String.format(Locale.US, "$%.2f USDT", pnlEstimado),
                        color = Color.White, // Destacado en blanco
                        fontSize = 24.sp, // Gigante
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
fun AlertaDatoItem(icono: String, label: String, valor: String, colorTexto: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icono, fontSize = 16.sp, modifier = Modifier.width(24.dp))
            Text(text = "$label:", color = colorTexto, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            text = valor,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}