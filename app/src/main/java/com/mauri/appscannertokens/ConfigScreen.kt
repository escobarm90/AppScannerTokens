package com.mauri.appscannertokens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(onBack: () -> Unit) {
    val viewModel: ConfigViewModel = viewModel()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Estilo de colores (Aesthetic Sync con tu logo verde/oscuro)
    val colorFondo = Color(0xFF0D1117)
    val colorVerdeProfit = Color(0xFF2EA043)
    val colorTextoSecundario = Color(0xFF8B949E)
    val colorBorde = Color(0xFF30363D)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración Avanzada", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Volver", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (viewModel.guardarConfiguracion()) {
                            Toast.makeText(context, "Configuración guardada ✅", Toast.LENGTH_SHORT).show()
                            onBack() // Volver automáticamente
                        } else {
                            Toast.makeText(context, "Error al guardar ❌", Toast.LENGTH_LONG).show()
                        }
                    }) {
                        Icon(Icons.Default.Check, "Guardar", tint = colorVerdeProfit)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorFondo)
            )
        },
        containerColor = colorFondo
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // --- SECCIÓN 1: BINANCE API (CRÍTICO) ---
            SeccionTitulo("Credenciales Binance API", colorTextoSecundario)

            OutlinedTextField(
                value = viewModel.apiKey,
                onValueChange = { viewModel.apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine = true,
                colors = inputColors(colorVerdeProfit, colorBorde)
            )

            OutlinedTextField(
                value = viewModel.secretKey,
                onValueChange = { viewModel.secretKey = it },
                label = { Text("Secret Key") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(), // Ocultar secreto
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = inputColors(colorVerdeProfit, colorBorde)
            )

            // --- SECCIÓN 2: GESTIÓN DE RIESGO ---
            SeccionTitulo("Gestión de Riesgo y Operación", colorTextoSecundario)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Apalancamiento (Entero)
                OutlinedTextField(
                    value = viewModel.apalancamiento,
                    onValueChange = { if (it.all { c -> c.isDigit() }) viewModel.apalancamiento = it },
                    label = { Text("Apalancamiento (x)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = inputColors(colorVerdeProfit, colorBorde)
                )
                // ROI Mínimo (Decimal)
                OutlinedTextField(
                    value = viewModel.roiMinimo,
                    onValueChange = { viewModel.roiMinimo = it.replace(',', '.') },
                    label = { Text("ROI Mínimo (%)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = inputColors(colorVerdeProfit, colorBorde)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Billetera Manual (Fallback si API falla)
            OutlinedTextField(
                value = viewModel.billeteraManual,
                onValueChange = { viewModel.billeteraManual = it.replace(',', '.') },
                label = { Text("Billetera Manual USDT (Fallback)") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = inputColors(colorVerdeProfit, colorBorde)
            )

            // --- SECCIÓN 3: ESTRATEGIA (Temporalidad) ---
            SeccionTitulo("Estrategia", colorTextoSecundario)

            Text(text = "Temporalidad de Velas (Timeframe)", color = Color.White, modifier = Modifier.padding(bottom = 8.dp))

            TimeframeDropdownSelector(
                opciones = viewModel.opcionesTimeframe,
                seleccionado = viewModel.timeframeSeleccionado,
                onSeleccion = { viewModel.timeframeSeleccionado = it },
                colorFondo = colorFondo,
                colorVerde = colorVerdeProfit,
                colorBorde = colorBorde
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Advertencia
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Color(0xFF2C1C01)).padding(8.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE3B341))
                Spacer(Modifier.width(8.dp))
                Text("Al cambiar la API Key o la Temporalidad, el motor del Escáner se reiniciará para aplicar los cambios.", color = Color(0xFFE3B341), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SeccionTitulo(titulo: String, colorTexto: Color) {
    Column {
        Text(
            text = titulo.uppercase(),
            color = colorTexto,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        HorizontalDivider(color = Color(0xFF30363D), modifier = Modifier.padding(bottom = 16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeframeDropdownSelector(
    opciones: List<String>,
    seleccionado: String,
    onSeleccion: (String) -> Unit,
    colorFondo: Color,
    colorVerde: Color,
    colorBorde: Color
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = seleccionado,
            onValueChange = {},
            readOnly = true, // No escribir, solo seleccionar
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = inputColors(colorVerde, colorBorde),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF161B22)) // Un poco más claro que el fondo
        ) {
            opciones.forEach { timeframe ->
                DropdownMenuItem(
                    text = { Text(text = timeframe, color = Color.White) },
                    onClick = {
                        onSeleccion(timeframe)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun inputColors(verde: Color, borde: Color) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = verde,
    unfocusedBorderColor = borde,
    focusedLabelColor = verde,
    unfocusedLabelColor = borde,
    cursorColor = verde
)