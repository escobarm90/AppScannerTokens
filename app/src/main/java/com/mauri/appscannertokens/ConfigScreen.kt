package com.mauri.appscannertokens

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: ConfigViewModel) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
    ) {
        TopAppBar(
            title = { Text("Configuración", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { (context as? Activity)?.finish() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
        )

        // Todo el contenido dentro de una columna scrolleable
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text("Conexión Binance", color = Color(0xFF2EA043), fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "💡 Saldo de la billetera: Puede crear una Api Key en su cuenta de Binance habilitando solo el permiso de “Enable Reading” para que la aplicación sepa en tiempo real el saldo exacto de su billetera y pueda ser más preciso con los cálculos, pero al mismo tiempo sea seguro en cuanto a que lo único que puede hacer con esa API es leer el saldo, no corre ningún riesgo.",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            OutlinedTextField(
                value = viewModel.apiKey,
                onValueChange = { viewModel.apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
            )

            OutlinedTextField(
                value = viewModel.secretKey,
                onValueChange = { viewModel.secretKey = it },
                label = { Text("Secret Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
            )

            Button(
                onClick = { viewModel.validarCredenciales() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                enabled = !viewModel.validandoApi
            ) {
                Text(if (viewModel.validandoApi) "Comprobando..." else "Validar API de Binance", color = Color.White)
            }

            if (viewModel.mensajeValidacion.isNotEmpty()) {
                Text(
                    text = viewModel.mensajeValidacion,
                    color = if (viewModel.mensajeValidacion.contains("✅")) Color(0xFF2EA043) else Color(0xFFF85149),
                    fontSize = 14.sp
                )
            }

            HorizontalDivider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 4.dp))

            Text("Estrategia y Riesgo", color = Color(0xFF2EA043), fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownTimeframe(
                    selected = viewModel.timeframe,
                    onSelectedChange = { viewModel.timeframe = it },
                    modifier = Modifier.weight(1f)
                )

                StepperField(
                    label = "Apalancamiento (x)",
                    value = viewModel.apalancamiento,
                    onValueChange = { viewModel.apalancamiento = it },
                    step = 1f,
                    min = 1f,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepperField(
                    label = "% Billetera (Margen)",
                    value = viewModel.tamanoPosPct,
                    onValueChange = { viewModel.tamanoPosPct = it },
                    step = 1f,
                    min = 1f,
                    modifier = Modifier.weight(1f)
                )

                StepperField(
                    label = "% Pérdida Máx (SL)",
                    value = viewModel.perdidaMaxPct,
                    onValueChange = { viewModel.perdidaMaxPct = it },
                    step = 0.5f,
                    min = 0.5f,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = viewModel.billeteraManual,
                    onValueChange = { viewModel.billeteraManual = it },
                    label = { Text("Billetera Simulada ($)", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.Gray)
                )

                StepperField(
                    label = "ROI Mínimo (%)",
                    value = viewModel.roiMinimo,
                    onValueChange = { viewModel.roiMinimo = it },
                    step = 1f,
                    min = 1f,
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Tipo de Margen", color = Color.White, fontSize = 14.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.tipoMargen = "ISOLATED" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.tipoMargen == "ISOLATED") Color(0xFF2EA043) else Color(0xFF30363D)
                    )
                ) {
                    Text("Aislado", color = Color.White)
                }
                Button(
                    onClick = { viewModel.tipoMargen = "CROSS" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.tipoMargen == "CROSS") Color(0xFF2EA043) else Color(0xFF30363D)
                    )
                ) {
                    Text("Cruzado", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.guardarConfiguracion()
                    Toast.makeText(context, "Configuración guardada", Toast.LENGTH_SHORT).show()
                    (context as? Activity)?.finish()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2EA043))
            ) {
                Text("Guardar y Volver", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(30.dp)) // Espacio final extra para scrollear cómodo
        }
    }
}

// COMPONENTE: Menú desplegable para el Timeframe
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownTimeframe(
    selected: String,
    onSelectedChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // Nota: Binance no soporta 10m en los WebSockets, por eso usamos 15m.
    val options = listOf("3m", "5m", "15m", "30m", "1h")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Timeframe", fontSize = 12.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF2EA043)
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF161B22))
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = Color.White) },
                    onClick = {
                        onSelectedChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

// COMPONENTE: Botones de + y - para campos numéricos
@Composable
fun StepperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    step: Float,
    min: Float = 0f,
    modifier: Modifier = Modifier
) {
    val numValue = value.toFloatOrNull() ?: 0f
    Column(modifier = modifier) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22), shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp)
        ) {
            TextButton(
                onClick = {
                    val newVal = maxOf(min, numValue - step)
                    onValueChange(String.format(Locale.US, "%.1f", newVal).replace(".0", ""))
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(40.dp)
            ) {
                Text("-", color = Color(0xFF2EA043), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
                singleLine = true
            )

            TextButton(
                onClick = {
                    val newVal = numValue + step
                    onValueChange(String.format(Locale.US, "%.1f", newVal).replace(".0", ""))
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(40.dp)
            ) {
                Text("+", color = Color(0xFF2EA043), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}