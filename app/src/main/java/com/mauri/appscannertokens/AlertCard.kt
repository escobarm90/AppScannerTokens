package com.mauri.appscannertokens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertCard(
    alerta: AlertData,
    config: UserConfig,
    billetera: Double,
    onExecuteLimit: suspend (AlertExecutionRequest) -> String,
    onExecuteMarket: suspend (AlertExecutionRequest) -> String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var porcentajeUsado by remember { mutableFloatStateOf(config.porcentajeInversion.toFloat()) }
    var isCrossMargin by remember { mutableStateOf(config.tipoMargen == "CROSSED") }
    var isExecuting by remember { mutableStateOf(false) }

    val marginType = if (isCrossMargin) "CROSSED" else "ISOLATED"
    val sizing = OrderSizingCalculator.calculate(alerta, config, billetera, porcentajeUsado.toDouble())

    val colorBackground = Color(0xFF161b22)
    val colorBorder = Color(0xFF30363d)
    val colorGreen = Color(0xFF2ea043)
    val colorRed = Color(0xFFda3633)
    val colorCyan = Color(0xFF58a6ff)
    val colorYellow = Color(0xFFe3b341)
    val time = remember(alerta.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(alerta.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = colorBackground),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(alerta.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("$time | Apalancado x${config.apalancamiento}", color = Color.Gray, fontSize = 12.sp)
                }
                Box(
                    modifier = Modifier
                        .background(
                            if (alerta.senal == "LONG") colorGreen.copy(alpha = 0.15f) else colorRed.copy(alpha = 0.15f),
                            RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            if (alerta.senal == "LONG") colorGreen else colorRed,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        alerta.senal,
                        color = if (alerta.senal == "LONG") colorGreen else colorRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            DataRow("ENTRADA", String.format(Locale.US, "%.6f", alerta.precio), colorCyan)
            DataRow("TAKE PROFIT", String.format(Locale.US, "%.6f", alerta.tp), colorGreen)
            DataRow("STOP LOSS", String.format(Locale.US, "%.6f", alerta.sl), colorRed)

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorder)
            Text("EJECUTAR ORDEN", color = colorCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Modo de margen:", color = Color.LightGray, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Aislado",
                        color = if (!isCrossMargin) colorYellow else Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = if (!isCrossMargin) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isCrossMargin,
                        onCheckedChange = { isCrossMargin = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colorCyan,
                            checkedTrackColor = colorCyan.copy(alpha = 0.4f),
                            uncheckedThumbColor = colorYellow,
                            uncheckedTrackColor = colorYellow.copy(alpha = 0.4f)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Cruzado",
                        color = if (isCrossMargin) colorCyan else Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = if (isCrossMargin) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Text("Invertir: ${porcentajeUsado.toInt()}% de tu billetera", color = Color.White, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { if (porcentajeUsado > 5f) porcentajeUsado -= 1f },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("-", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
                Slider(
                    value = porcentajeUsado,
                    onValueChange = { porcentajeUsado = kotlin.math.round(it) },
                    valueRange = 5f..100f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = if (isCrossMargin) colorCyan else colorYellow,
                        activeTrackColor = if (isCrossMargin) colorCyan else colorYellow
                    )
                )
                IconButton(
                    onClick = { if (porcentajeUsado < 100f) porcentajeUsado += 1f },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExecuteButton(
                    label = "LIMIT",
                    enabled = !isExecuting,
                    containerColor = colorCyan,
                    textColor = colorBackground,
                    onClick = {
                        executeOrder(
                            config = config,
                            alerta = alerta,
                            walletPercent = porcentajeUsado.toDouble(),
                            marginType = marginType,
                            isExecuting = { isExecuting = it },
                            execute = onExecuteLimit,
                            showMessage = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                            coroutineScope = coroutineScope
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                ExecuteButton(
                    label = "MARKET",
                    enabled = !isExecuting,
                    containerColor = colorGreen,
                    textColor = Color.White,
                    onClick = {
                        executeOrder(
                            config = config,
                            alerta = alerta,
                            walletPercent = porcentajeUsado.toDouble(),
                            marginType = marginType,
                            isExecuting = { isExecuting = it },
                            execute = onExecuteMarket,
                            showMessage = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                            coroutineScope = coroutineScope
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                border = androidx.compose.foundation.BorderStroke(1.dp, colorBorder)
            ) {
                Text("DESCARTAR SENAL", color = Color.LightGray)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = colorBorder)
            Text("RESULTADOS ESTIMADOS", color = colorYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (sizing.riskWasReduced) {
                Text(
                    "Riesgo excedido: posicion ajustada automaticamente.",
                    color = colorRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            DataRow("Margen a usar ($marginType)", String.format(Locale.US, "${'$'}%.2f USDT", sizing.marginUsdt), Color.White)
            DataRow("Tamano apalancado", String.format(Locale.US, "${'$'}%.2f USDT", sizing.notionalUsdt), Color.LightGray)
            DataRow("Fee apertura est.", String.format(Locale.US, "-$%.4f USDT", sizing.entryFeeUsdt), colorRed.copy(alpha = 0.8f))
            DataRow("Fee cierre est.", String.format(Locale.US, "-$%.4f USDT", sizing.exitFeeUsdt), colorRed.copy(alpha = 0.8f))
            DataRow(
                "PNL bruto est.",
                String.format(Locale.US, "${'$'}%.2f USDT (+%.2f%%)", sizing.grossPnlUsdt, sizing.roePct),
                Color.LightGray
            )
            DataRow(
                "PNL neto est.",
                String.format(Locale.US, "${'$'}%.2f USDT", sizing.netPnlUsdt),
                if (sizing.netPnlUsdt > 0) colorGreen else colorRed
            )
        }
    }
}

@Composable
private fun ExecuteButton(
    label: String,
    enabled: Boolean,
    containerColor: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor)
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

private fun executeOrder(
    config: UserConfig,
    alerta: AlertData,
    walletPercent: Double,
    marginType: String,
    isExecuting: (Boolean) -> Unit,
    execute: suspend (AlertExecutionRequest) -> String,
    showMessage: (String) -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    if (config.apiKey.isBlank() || config.apiSecret.isBlank()) {
        showMessage("Faltan API Keys")
        return
    }

    isExecuting(true)
    coroutineScope.launch {
        try {
            val message = execute(
                AlertExecutionRequest(
                    alert = alerta,
                    walletPercent = walletPercent,
                    marginType = marginType
                )
            )
            showMessage(message)
        } catch (e: Exception) {
            showMessage("Error ejecutando orden: ${e.message}")
        } finally {
            isExecuting(false)
        }
    }
}

@Composable
fun DataRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
