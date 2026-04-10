package com.mauri.appscannertokens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AlertCard(
    alerta: AlertData,
    config: UserConfig,
    billetera: Double,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var porcentajeUsado by remember { mutableStateOf(30f) }
    var isCruzado by remember { mutableStateOf(false) }
    val tipoMargen = if (isCruzado) "CROSSED" else "ISOLATED"
    var isEjecutando by remember { mutableStateOf(false) }

    val colorFondo = Color(0xFF161b22)
    val colorBorde = Color(0xFF30363d)
    val colorGreen = Color(0xFF2ea043)
    val colorRed = Color(0xFFda3633)
    val colorCyan = Color(0xFF58a6ff)
    val colorYellow = Color(0xFFe3b341)

    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val horaStr = formatter.format(Date(alerta.timestamp))

    // ========================================================
    // MATEMÁTICA EXACTA DEL PNL Y TAMAÑO DE POSICIÓN
    // ========================================================
    val margenCalculado = if (billetera > 0) billetera * (porcentajeUsado / 100.0) else 0.0
    val nominalCalculado = margenCalculado * config.apalancamiento

    // Distancia en porcentaje desde la entrada hasta el Take Profit
    val distanciaTpPct = if (alerta.precio > 0) Math.abs(alerta.tp - alerta.precio) / alerta.precio else 0.0

    // Ganancia = Tamaño de posición * % de movimiento del precio
    val pnlCalculado = nominalCalculado * distanciaTpPct

    // ROE (Return on Equity) = Ganancia / Margen Invertido
    val roeCalculado = distanciaTpPct * config.apalancamiento * 100.0

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = colorFondo),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- HEADER ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("🪙 ${alerta.symbol}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("🕒 $horaStr | Apalancado x${config.apalancamiento}", color = Color.Gray, fontSize = 12.sp)
                }
                Box(modifier = Modifier.background(if (alerta.senal == "LONG") colorGreen.copy(alpha = 0.15f) else colorRed.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).border(1.dp, if (alerta.senal == "LONG") colorGreen else colorRed, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("🎯 ${alerta.senal}", color = if (alerta.senal == "LONG") colorGreen else colorRed, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- PRECIOS OBJETIVO ---
            DataRow("💵 ENTRADA", String.format(Locale.US, "%.6f", alerta.precio), colorCyan)
            DataRow("✅ TAKE PROFIT", String.format(Locale.US, "%.6f", alerta.tp), colorGreen)
            DataRow("❌ STOP LOSS", String.format(Locale.US, "%.6f", alerta.sl), colorRed)

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorde)

            // --- CONTROLES DE EJECUCIÓN ---
            Text("⚡ EJECUTAR ORDEN RÁPIDA", color = colorCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Modo de Margen:", color = Color.LightGray, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aislado", color = if (!isCruzado) colorYellow else Color.Gray, fontSize = 12.sp, fontWeight = if (!isCruzado) FontWeight.Bold else FontWeight.Normal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = isCruzado, onCheckedChange = { isCruzado = it }, colors = SwitchDefaults.colors(checkedThumbColor = colorCyan, checkedTrackColor = colorCyan.copy(alpha = 0.4f), uncheckedThumbColor = colorYellow, uncheckedTrackColor = colorYellow.copy(alpha = 0.4f)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cruzado", color = if (isCruzado) colorCyan else Color.Gray, fontSize = 12.sp, fontWeight = if (isCruzado) FontWeight.Bold else FontWeight.Normal)
                }
            }

            Text("Invertir: ${porcentajeUsado.toInt()}% de tu billetera", color = Color.White, fontSize = 14.sp)
            Slider(
                value = porcentajeUsado, onValueChange = { porcentajeUsado = it }, valueRange = 5f..100f, steps = 19,
                colors = SliderDefaults.colors(thumbColor = if (isCruzado) colorCyan else colorYellow, activeTrackColor = if (isCruzado) colorCyan else colorYellow)
            )

            // --- BOTONES DE COMPRA/VENTA ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val side = if (alerta.senal == "LONG") "BUY" else "SELL"
                val cantidadMonedas = if (alerta.precio > 0) nominalCalculado / alerta.precio else 0.0

                Button(
                    onClick = {
                        if (config.apiKey.isEmpty() || config.apiSecret.isEmpty()) {
                            Toast.makeText(context, "Configura tus API Keys primero", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isEjecutando = true
                        coroutineScope.launch {
                            val (exito, msj) = BinanceApiManager.ejecutarOrden(
                                config.apiKey, config.apiSecret, alerta.symbol, side, "LIMIT", cantidadMonedas, alerta.precio, tipoMargen
                            )
                            Toast.makeText(context, msj, Toast.LENGTH_LONG).show()
                            isEjecutando = false
                        }
                    },
                    modifier = Modifier.weight(1f), enabled = !isEjecutando,
                    colors = ButtonDefaults.buttonColors(containerColor = colorCyan)
                ) {
                    Text(if (isEjecutando) "..." else "📝 LIMIT", color = colorFondo, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        if (config.apiKey.isEmpty() || config.apiSecret.isEmpty()) {
                            Toast.makeText(context, "Configura tus API Keys primero", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isEjecutando = true
                        coroutineScope.launch {
                            val (exito, msj) = BinanceApiManager.ejecutarOrden(
                                config.apiKey, config.apiSecret, alerta.symbol, side, "MARKET", cantidadMonedas, alerta.precio, tipoMargen
                            )
                            Toast.makeText(context, msj, Toast.LENGTH_LONG).show()
                            isEjecutando = false
                        }
                    },
                    modifier = Modifier.weight(1f), enabled = !isEjecutando,
                    colors = ButtonDefaults.buttonColors(containerColor = colorGreen)
                ) {
                    Text(if (isEjecutando) "..." else "🚀 MARKET", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            // BOTÓN DESCARTAR
            Button(
                onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)
            ) {
                Text("❌ DESCARTAR SEÑAL", color = Color.LightGray)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorde)

            // --- RESULTADOS ESTIMADOS (CON PNL REAL) ---
            Text("🏦 RESULTADOS ESTIMADOS", color = colorYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            DataRow("Margen a Usar ($tipoMargen)", String.format(Locale.US, "$%.2f USDT", margenCalculado), Color.White)

            // Fila de Ganancia + ROE %
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PNL Neto Est. (Si toca TP)", color = Color.Gray, fontSize = 14.sp)
                Text(
                    text = String.format(Locale.US, "$%.2f USDT (+%.2f%%)", pnlCalculado, roeCalculado),
                    color = colorGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun DataRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}