package com.mauri.appscannertokens

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

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

@Composable
fun MonitorScreen(onOpenConfig: () -> Unit) {
    val alertas by AlertManager.alertas.collectAsState()
    val logsConsola by AlertManager.logs.collectAsState()
    val context = LocalContext.current

    val colorFondo = Color(0xFF0d1117)
    val colorCard = Color(0xFF161b22)
    val colorBorde = Color(0xFF30363d)

    var isMotorRunning by remember { mutableStateOf(TradingScannerService.isRunning) }
    var isDebugEnabled by remember { mutableStateOf(TradingScannerService.isDebugMode) }

    Column(modifier = Modifier.fillMaxSize().background(colorFondo).padding(16.dp)) {

        // --- HEADER ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("📡 Dashboard", color = Color(0xFF58a6ff), fontSize = 24.sp, fontWeight = FontWeight.Bold)
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

        // --- PANEL DE CONTROL ---
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colorCard), border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("🚀 Motor de Escaneo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(if (isMotorRunning) "Activo - Analizando" else "Detenido", color = if (isMotorRunning) Color(0xFF2ea043) else Color.Gray, fontSize = 12.sp)
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
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = colorBorde)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("💻 Consola CMD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Ver cálculos en tiempo real", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isDebugEnabled,
                        onCheckedChange = { isDebugEnabled = it; TradingScannerService.isDebugMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFe3b341), checkedTrackColor = Color(0xFFe3b341).copy(alpha = 0.5f))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- ZONA DIVIDIDA (ALERTAS Y TERMINAL) ---
        Column(modifier = Modifier.fillMaxSize()) {

            // 1. ZONA DE ALERTAS
            Text("ÚLTIMAS OPORTUNIDADES", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.weight(if (isDebugEnabled) 0.5f else 1f).fillMaxWidth()) {
                if (alertas.isEmpty()) {
                    Text(if (isMotorRunning) "Esperando cruces perfectos..." else "Motor apagado.", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(alertas) { alerta ->
                            AlertCard(alerta = alerta, onDismiss = { AlertManager.removerAlerta(alerta) })
                        }
                    }
                }
            }

            // 2. ZONA DE CONSOLA CMD (Solo visible si Debug está encendido)
            if (isDebugEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("TERMINAL EN VIVO", color = Color(0xFFe3b341), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.weight(0.5f).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.Black), // Fondo negro de consola
                    border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        reverseLayout = true // Hace que el texto nuevo empuje desde abajo hacia arriba (Efecto Matrix)
                    ) {
                        items(logsConsola) { logMsg ->
                            Text(
                                text = logMsg,
                                color = Color(0xFF00FF00), // Verde Neón
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