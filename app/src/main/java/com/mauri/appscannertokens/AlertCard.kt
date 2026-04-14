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

    var porcentajeUsado by remember { mutableFloatStateOf(30f) }
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

    val margenCalculado = if (billetera > 0) billetera * (porcentajeUsado / 100.0) else 0.0
    val nominalCalculado = margenCalculado * config.apalancamiento
    val distanciaTpPct = if (alerta.precio > 0) kotlin.math.abs(alerta.tp - alerta.precio) / alerta.precio else 0.0
    val pnlBrutoCalculado = nominalCalculado * distanciaTpPct
    val roeCalculado = distanciaTpPct * config.apalancamiento * 100.0

    // Cálculo de comisiones (Estimamos 0.05% Taker para apertura y 0.05% para cierre)
    val feeAperturaEst = nominalCalculado * 0.0005
    val feeCierreEst = nominalCalculado * 0.0005
    val totalFeesEst = feeAperturaEst + feeCierreEst
    val pnlNetoCalculado = pnlBrutoCalculado - totalFeesEst

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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Botón -
                IconButton(
                    onClick = { if (porcentajeUsado > 5f) porcentajeUsado -= 1f },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("-", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }

                // Slider Sensible
                Slider(
                    value = porcentajeUsado,
                    onValueChange = { porcentajeUsado = kotlin.math.round(it) },
                    valueRange = 5f..100f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = if (isCruzado) colorCyan else colorYellow,
                        activeTrackColor = if (isCruzado) colorCyan else colorYellow
                    )
                )

                // Botón +
                IconButton(
                    onClick = { if (porcentajeUsado < 100f) porcentajeUsado += 1f },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
            }

// --- BOTONES COMPRA/VENTA (CON INICIO DE TRAILING) ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val side = if (alerta.senal == "LONG") "BUY" else "SELL"
                val cantidadMonedas = if (alerta.precio > 0) nominalCalculado / alerta.precio else 0.0

                Button(
                    onClick = {
                        if (config.apiKey.isEmpty() || config.apiSecret.isEmpty()) {
                            Toast.makeText(context, "Faltan API Keys", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isEjecutando = true
                        coroutineScope.launch {
                            // Extraemos el orderId de la nueva función Triple
                            val (exito, msj, orderId) = BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, alerta.symbol, side, "LIMIT", cantidadMonedas, alerta.precio, tipoMargen)
                            Toast.makeText(context, msj, Toast.LENGTH_LONG).show()

                            if (exito && orderId > 0) {
                                // Delegamos la vigilancia y la colocación de SL/TP al PositionManager
                                PositionManager.iniciarMonitoreo(config, alerta.symbol, alerta.senal, alerta.precio, alerta.tp, alerta.sl, config.apalancamiento, orderId)
                                onDismiss()
                            }
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
                            Toast.makeText(context, "Faltan API Keys", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isEjecutando = true
                        coroutineScope.launch {
                            // Extraemos el orderId de la nueva función Triple
                            val (exito, msj, orderId) = BinanceApiManager.ejecutarOrden(config.apiKey, config.apiSecret, alerta.symbol, side, "MARKET", cantidadMonedas, alerta.precio, tipoMargen)
                            Toast.makeText(context, msj, Toast.LENGTH_LONG).show()

                            if (exito && orderId > 0) {
                                // Delegamos la vigilancia y la colocación de SL/TP al PositionManager
                                PositionManager.iniciarMonitoreo(config, alerta.symbol, alerta.senal, alerta.precio, alerta.tp, alerta.sl, config.apalancamiento, orderId)
                                onDismiss()
                            }
                            isEjecutando = false
                        }
                    },
                    modifier = Modifier.weight(1f), enabled = !isEjecutando,
                    colors = ButtonDefaults.buttonColors(containerColor = colorGreen)
                ) {
                    Text(if (isEjecutando) "..." else "🚀 MARKET", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Button(
                onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), border = androidx.compose.foundation.BorderStroke(1.dp, colorBorde)
            ) {
                Text("❌ DESCARTAR SEÑAL", color = Color.LightGray)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorde)

            // --- RESULTADOS ESTIMADOS ---
            Text("🏦 RESULTADOS ESTIMADOS", color = colorYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            DataRow("Margen a Usar ($tipoMargen)", String.format(Locale.US, "$%.2f USDT", margenCalculado), Color.White)
            DataRow("Tamaño de Posición (Apalancado)", String.format(Locale.US, "$%.2f USDT", nominalCalculado), Color.LightGray)

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = colorBorde.copy(alpha = 0.5f))

            // Detalle de comisiones
            Text("💸 Gastos de Operación (Fees Binance)", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            DataRow("Fee Apertura Est. (0.05%)", String.format(Locale.US, "-$%.4f USDT", feeAperturaEst), colorRed.copy(alpha = 0.8f))
            DataRow("Fee Cierre Est. (0.05%)", String.format(Locale.US, "-$%.4f USDT", feeCierreEst), colorRed.copy(alpha = 0.8f))

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = colorBorde.copy(alpha = 0.5f))

            // PNL Bruto y Neto
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PNL Bruto Est. (Al tocar TP)", color = Color.Gray, fontSize = 13.sp)
                Text(
                    text = String.format(Locale.US, "$%.2f USDT (+%.2f%%)", pnlBrutoCalculado, roeCalculado),
                    color = Color.LightGray, fontWeight = FontWeight.Normal, fontSize = 13.sp
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PNL NETO (Ganancia Limpia)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = String.format(Locale.US, "$%.2f USDT", pnlNetoCalculado),
                    color = if (pnlNetoCalculado > 0) colorGreen else colorRed, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp
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