package com.mauri.appscannertokens;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class TradingScannerService extends Service {

    private static final String CHANNEL_ID = "ScannerServiceChannel";
    private boolean isScanning = false;
    private OkHttpClient client;
    private WebSocket webSocket;

    private Map<String, BarSeries> mercado = new ConcurrentHashMap<>();

    private static final int TOP_VOLATILES = 50;
    private static final double VOLUMEN_MIN_24H = 15000000.0;

    // Configuración de Capital y Riesgo
    private static final double BILLETERA_DEFAULT = 20.0;
    private static final double PORCENTAJE_INVERSION = 0.30;
    private static final double APALANCAMIENTO_ACTUAL = 20.0;
    private static final double MULTIPLICADOR_TP = 2.0;
    private static final double MULTIPLICADOR_SL = 1.5;

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanalNotificacion();
        client = new OkHttpClient();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Motor Scanner Activo")
                .setContentText("Monitoreando pares en vivo...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();

        startForeground(1, notification);

        if (!isScanning) {
            iniciarMotorBinance();
            isScanning = true;
        }

        return START_STICKY;
    }

    private void iniciarMotorBinance() {
        new Thread(() -> {
            try {
                enviarDebug("🚀 Iniciando Motor REST...");
                List<String> topPares = obtenerTopPares();
                enviarDebug("✅ Top " + topPares.size() + " pares filtrados. Descargando historial...");

                for (String symbol : topPares) {
                    descargarVelas(symbol);
                    Thread.sleep(50);
                }

                enviarDebug("✅ 250 velas descargadas por par.");
                enviarDebug("🔌 Conectando WebSockets en vivo...");

                iniciarWebsocketMultiplex(topPares);

            } catch (Exception e) {
                enviarDebug("❌ Error fatal: " + e.getMessage());
            }
        }).start();
    }

    private List<String> obtenerTopPares() throws Exception {
        List<String> resultados = new ArrayList<>();
        List<JsonObject> validTickers = new ArrayList<>();

        Request request = new Request.Builder().url("https://fapi.binance.com/fapi/v1/ticker/24hr").build();
        try (Response response = client.newCall(request).execute()) {
            String json = response.body().string();
            JsonArray tickers = JsonParser.parseString(json).getAsJsonArray();

            for (JsonElement elem : tickers) {
                JsonObject t = elem.getAsJsonObject();
                String symbol = t.get("symbol").getAsString();
                double volume = t.get("quoteVolume").getAsDouble();

                if (symbol.endsWith("USDT") && volume > VOLUMEN_MIN_24H) {
                    validTickers.add(t);
                }
            }

            validTickers.sort((a, b) -> {
                double v1 = Math.abs(a.get("priceChangePercent").getAsDouble());
                double v2 = Math.abs(b.get("priceChangePercent").getAsDouble());
                return Double.compare(v2, v1);
            });

            for (int i = 0; i < Math.min(TOP_VOLATILES, validTickers.size()); i++) {
                resultados.add(validTickers.get(i).get("symbol").getAsString());
            }
        }
        return resultados;
    }

    private void descargarVelas(String symbol) throws Exception {
        String url = "https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol + "&interval=3m&limit=250";
        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            String json = response.body().string();
            JsonArray klines = JsonParser.parseString(json).getAsJsonArray();

            BarSeries serie = new BaseBarSeriesBuilder().withName(symbol).withMaxBarCount(250).build();

            for (JsonElement elem : klines) {
                JsonArray k = elem.getAsJsonArray();
                long closeTime = k.get(6).getAsLong();
                double open = k.get(1).getAsDouble();
                double high = k.get(2).getAsDouble();
                double low = k.get(3).getAsDouble();
                double close = k.get(4).getAsDouble();
                double vol = k.get(5).getAsDouble();

                ZonedDateTime endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(closeTime), ZoneId.systemDefault());
                serie.addBar(new BaseBar(Duration.ofMinutes(3), endTime, serie.numOf(open), serie.numOf(high), serie.numOf(low), serie.numOf(close), serie.numOf(vol), serie.numOf(0)));
            }
            mercado.put(symbol, serie);
        }
    }

    private void iniciarWebsocketMultiplex(List<String> pares) {
        StringBuilder streams = new StringBuilder();
        for (int i = 0; i < pares.size(); i++) {
            streams.append(pares.get(i).toLowerCase()).append("@kline_3m");
            if (i < pares.size() - 1) streams.append("/");
        }

        String wssUrl = "wss://fstream.binance.com/stream?streams=" + streams.toString();
        Request request = new Request.Builder().url(wssUrl).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                if (!json.has("data")) return;

                JsonObject data = json.getAsJsonObject("data");
                String symbol = data.get("s").getAsString();
                JsonObject kline = data.getAsJsonObject("k");

                if (!mercado.containsKey(symbol)) return;

                long closeTime = kline.get("T").getAsLong();
                double open = kline.get("o").getAsDouble();
                double high = kline.get("h").getAsDouble();
                double low = kline.get("l").getAsDouble();
                double close = kline.get("c").getAsDouble();
                double vol = kline.get("v").getAsDouble();
                boolean isClosed = kline.get("x").getAsBoolean();

                ZonedDateTime endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(closeTime), ZoneId.systemDefault());

                // 1. PRIMERO traemos la serie de la memoria
                BarSeries serie = mercado.get(symbol);

                // 2. AHORA SÍ armamos la vela usando la precisión de la serie
                Bar vela = new BaseBar(Duration.ofMinutes(3), endTime, serie.numOf(open), serie.numOf(high), serie.numOf(low), serie.numOf(close), serie.numOf(vol), serie.numOf(0));

                // 3. Y la agregamos
                serie.addBar(vela, !isClosed);

                calcularIndicadores(symbol, serie);
            }
        });
    }

    private void calcularIndicadores(String symbol, BarSeries series) {
        if (series.getBarCount() < 50) return;

        int ultima = series.getEndIndex();
        int previa = ultima - 1;

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        EMAIndicator ema9 = new EMAIndicator(closePrice, 9);
        EMAIndicator ema200 = new EMAIndicator(closePrice, 200);
        ATRIndicator atr = new ATRIndicator(series, 14);

        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        StandardDeviationIndicator sd20 = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, sd20, series.numOf(2.0));
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd20, series.numOf(2.0));

        double precioActual = closePrice.getValue(ultima).doubleValue();
        double precioApertura = series.getBar(ultima).getOpenPrice().doubleValue();
        double precioMinimo = series.getBar(ultima).getLowPrice().doubleValue();
        double precioMaximo = series.getBar(ultima).getHighPrice().doubleValue();

        double valorEma9 = ema9.getValue(ultima).doubleValue();
        double valorEma200 = ema200.getValue(ultima).doubleValue();
        double valorBbLower = bbLower.getValue(ultima).doubleValue();
        double valorBbUpper = bbUpper.getValue(ultima).doubleValue();
        double valorAtr = atr.getValue(ultima).doubleValue();

        double cierrePrevio = closePrice.getValue(previa).doubleValue();
        double minPrevio = series.getBar(previa).getLowPrice().doubleValue();
        double maxPrevio = series.getBar(previa).getHighPrice().doubleValue();
        double ema9Previa = ema9.getValue(previa).doubleValue();
        double bbLowerPrevio = bbLower.getValue(previa).doubleValue();
        double bbUpperPrevio = bbUpper.getValue(previa).doubleValue();

        boolean velaVerde = precioActual > precioApertura;
        boolean velaRoja = precioActual < precioApertura;

        boolean toqueBbInf = (minPrevio <= bbLowerPrevio) || (precioMinimo <= valorBbLower);
        boolean recuperaEma9 = (precioActual > valorEma9) && (cierrePrevio <= ema9Previa);

        boolean zonaBbUpper = (maxPrevio >= bbUpperPrevio) || (precioMaximo >= valorBbUpper);
        boolean pierdeEma9 = (precioActual < valorEma9) && (cierrePrevio >= ema9Previa);

        String senal = "NEUTRAL";
        if (toqueBbInf && recuperaEma9 && velaVerde) senal = "LONG";
        else if (zonaBbUpper && pierdeEma9 && velaRoja) senal = "SHORT";

        // Salida limpia para la consola
        String debugMsj = "╭────────────────────────────────────────╮\n" +
                "│ 🪙 TOKEN: " + symbol + "\n" +
                "│ 💵 PRECIO: " + String.format("%.4f", precioActual) + "\n" +
                "│ 📊 RSI (14): " + String.format("%.2f", rsi.getValue(ultima).doubleValue()) + "\n" +
                "│ 📈 EMA 9: " + String.format("%.4f", valorEma9) + "\n" +
                "│ 📉 EMA 200: " + String.format("%.4f", valorEma200) + "\n" +
                "│ 🎯 SEÑAL: " + senal + "\n" +
                "╰────────────────────────────────────────╯";
        enviarDebug(debugMsj);

        if (!senal.equals("NEUTRAL")) {
            // Matemática del PNL (Ganancias y Pérdidas)
            double margenPorOperacion = BILLETERA_DEFAULT * PORCENTAJE_INVERSION;
            double distTp = valorAtr * MULTIPLICADOR_TP;
            double distSl = valorAtr * MULTIPLICADOR_SL;

            double tp = senal.equals("LONG") ? precioActual + distTp : precioActual - distTp;
            double sl = senal.equals("LONG") ? precioActual - distSl : precioActual + distSl;

            double pnlNeto = (margenPorOperacion * APALANCAMIENTO_ACTUAL * (distTp / precioActual)) - (margenPorOperacion * APALANCAMIENTO_ACTUAL * 0.001);
            double roi = (pnlNeto / margenPorOperacion) * 100;

            String tituloPush = (senal.equals("LONG") ? "🟢" : "🔴") + " " + senal + " en " + symbol + " | PNL: $" + String.format("%.2f", pnlNeto);
            String cuerpoPush = "💵 ENTRADA: " + String.format("%.4f", precioActual) + "\n" +
                    "✅ TP: " + String.format("%.4f", tp) + "\n" +
                    "❌ SL: " + String.format("%.4f", sl) + "\n" +
                    "🏦 SALDO BILLETERA: $" + BILLETERA_DEFAULT + "\n" +
                    "🔥 ROI EST: " + String.format("%.2f", roi) + "%";

            Intent intent = new Intent("NUEVA_ALERTA");
            intent.setPackage(getPackageName()); // 🔥 La etiqueta de seguridad clave
            intent.putExtra("titulo", tituloPush);
            intent.putExtra("cuerpo", cuerpoPush);
            sendBroadcast(intent);

            hacerVibrar(senal);
        }
    }

    private void hacerVibrar(String senal) {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] patronLong = {0, 150, 100, 150}; // Vibración doble corta
            long[] patronShort = {0, 600};          // Vibración larga única
            long[] patron = senal.equals("LONG") ? patronLong : patronShort;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(patron, -1));
            } else {
                vibrator.vibrate(patron, -1);
            }
        }
    }

    private void enviarDebug(String msj) {
        Intent intent = new Intent("NUEVO_DEBUG");
        intent.setPackage(getPackageName()); // 🔥 La etiqueta de seguridad clave
        intent.putExtra("linea_debug", msj);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isScanning = false;
        if (webSocket != null) webSocket.cancel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Escáner Nativo", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
        }
    }
}