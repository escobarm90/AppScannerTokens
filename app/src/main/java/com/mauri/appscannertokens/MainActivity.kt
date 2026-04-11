package com.mauri.appscannertokens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MonitorScreen(onOpenConfig = { startActivity(Intent(this, ConfigActivity::class.java)) })
            }
        }
    }
}

fun cargarConfiguracion(context: Context): UserConfig {
    val prefs = context.getSharedPreferences("AppScannerConfig", Context.MODE_PRIVATE)
    val jsonGuardado = prefs.getString("config_data", null)
    return if (jsonGuardado != null) Gson().fromJson(jsonGuardado, UserConfig::class.java) else UserConfig()
}

@Composable
fun MonitorScreen(onOpenConfig: () -> Unit) {
    val alertas by AlertManager.alertas.collectAsState()
    val logsConsola by AlertManager.logs.collectAsState()
    val posicionesActivas by PositionManager.positions.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var config by remember { mutableStateOf(cargarConfiguracion(context)) }
    var saldoBilletera by remember { mutableStateOf(0.0) }
    var isLoadingSaldo by remember { mutableStateOf(false) }

    val colorFondo = Color(0xFF0d1117)
    val colorCard = Color(0xFF161b22)
    val colorBorde = Color(0xFF30363d)
    val colorPrimary = Color(0xFF58a6ff)

    var isMotorRunning by remember { mutableStateOf(TradingScannerService.isRunning) }
    var isDebugEnabled by remember { mutableStateOf(TradingScannerService.isDebugMode) }

    // Configuración del Pager (Pestañas Deslizables)
    val tabs = listOf("Oportunidades", "Posiciones", "Consola")
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    fun actualizarSaldo() {
        if (config.apiKey.isNotEmpty() && config.apiSecret.isNotEmpty()) {
            isLoadingSaldo = true
            coroutineScope.launch {
                saldoBilletera = BinanceApiManager.obtenerSaldoUSDT(config.apiKey, config.apiSecret)
                isLoadingSaldo = false
            }
        }
    }

    LaunchedEffect(Unit) { actualizarSaldo() }

    Column(modifier = Modifier.fillMaxSize().background(colorFondo).padding(top = 16.dp)) {

        // --- HEADER FIJO ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📡 Dashboard", color = colorPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = onOpenConfig,
                colors = ButtonDefaults.buttonColors(containerColor = colorCard),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("⚙️ Parámetros", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- MENÚ DE NAVEGACIÓN (TABS) ---
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = colorFondo,
            contentColor = colorPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                    color = colorPrimary
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text(
                            text = title,
                            color = if (pagerState.currentPage == index) colorPrimary else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        // --- CONTENIDO DESLIZABLE (PAGER) ---
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                // ==========================================
                // PESTAÑA 0: OPORTUNIDADES Y MOTOR
                // ==========================================
                0 -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        // BILLETERA
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1a2b22)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2ea043))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("💰 SALDO DISPONIBLE (USDT)", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    if (isLoadingSaldo) {
                                        Text("Consultando...", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Text(text = "$${String.format(Locale.US, "%.2f", saldoBilletera)}", color = Color(0xFF2ea043), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                                IconButton(onClick = { actualizarSaldo() }) { Text("🔄", fontSize = 20.sp) }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // SWITCH MOTOR
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colorCard), border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("🚀 Motor de Escaneo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(if (isMotorRunning) "Activo - Analizando Binance" else "Motor Detenido", color = if (isMotorRunning) Color(0xFF2ea043) else Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = isMotorRunning,
                                    onCheckedChange = {
                                        isMotorRunning = it; TradingScannerService.isRunning = it
                                        val intent = Intent(context, TradingScannerService::class.java)
                                        if (it) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF2ea043), checkedTrackColor = Color(0xFF2ea043).copy(alpha = 0.5f))
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // LISTA DE ALERTAS
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("ÚLTIMAS OPORTUNIDADES", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { config = cargarConfiguracion(context); actualizarSaldo() }) {
                                Text("🔄 Refrescar Config", color = colorPrimary, fontSize = 10.sp)
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            if (alertas.isEmpty()) {
                                Text(if (isMotorRunning) "Esperando cruces perfectos..." else "Enciende el motor arriba.", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(alertas) { alerta ->
                                        AlertCard(alerta = alerta, config = config, billetera = saldoBilletera, onDismiss = { AlertManager.removerAlerta(alerta) })
                                    }
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // PESTAÑA 1: POSICIONES EN VIVO
                // ==========================================
                1 -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("📈 POSICIONES ACTIVAS (${posicionesActivas.size})", color = Color(0xFFe3b341), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        if (posicionesActivas.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No hay posiciones abiertas.", color = Color.Gray)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(posicionesActivas) { pos ->
                                    val isGanancia = pos.pnlNeto >= 0
                                    val colorEstado = if (isGanancia) Color(0xFF2ea043) else Color(0xFFda3633)

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = if (pos.isClosed) Color(0xFF30363d) else colorCard),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (pos.isClosed) Color.Gray else colorEstado)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.SpaceBetween) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("${pos.symbol} [${pos.side}]", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                                Text("Nivel TS: ${pos.trailingLevel}", color = Color.Gray, fontSize = 12.sp)
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Column {
                                                    Text("Entrada: ${String.format(Locale.US, "%.5f", pos.entryPrice)}", color = Color.LightGray, fontSize = 14.sp)
                                                    Text("Actual:  ${String.format(Locale.US, "%.5f", pos.currentPrice)}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(String.format(Locale.US, "%.2f%%", pos.roePct), color = colorEstado, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                                    Text(String.format(Locale.US, "%.2f USDT", pos.pnlNeto), color = colorEstado, fontSize = 14.sp)
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))

                                            if (pos.isClosed) {
                                                Text("POSICIÓN CERRADA", color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                                            } else {
                                                val distTotal = abs(pos.dynamicTp - pos.entryPrice)
                                                val progreso = if (distTotal > 0) abs(pos.currentPrice - pos.entryPrice) / distTotal else 0.0
                                                LinearProgressIndicator(
                                                    progress = { progreso.toFloat().coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                                    color = colorEstado, trackColor = colorBorde
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // PESTAÑA 2: CONSOLA DEBUG
                // ==========================================
                2 -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

                        // SWITCH MODO DEBUG
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colorCard), border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("🐛 Modo Debug", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("Mostrar cálculos en pantalla", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = isDebugEnabled,
                                    onCheckedChange = { isDebugEnabled = it; TradingScannerService.isDebugMode = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFe3b341), checkedTrackColor = Color(0xFFe3b341).copy(alpha = 0.5f))
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("TERMINAL EN VIVO", color = Color(0xFFe3b341), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        // TERMINAL NEGRA
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            colors = CardDefaults.cardColors(containerColor = Color.Black),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), reverseLayout = true) {
                                items(logsConsola) { logMsg ->
                                    Text(
                                        text = logMsg,
                                        color = Color(0xFF00FF00),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}