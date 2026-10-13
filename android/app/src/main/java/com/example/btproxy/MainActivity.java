package com.example.btproxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    private TextView tvInstructions, tvLogs;
    private Button btnConnect, btnCopyLogs;
    private ScrollView scrollLogs;
    private Handler handler;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        
        tvInstructions = new TextView(this);
        tvInstructions.setTextSize(16);
        tvInstructions.setText("ИНСТРУКЦИЯ:\n1. Сопрягите Nokia и этот телефон.\n2. На Nokia нажмите 'Start Server'.\n3. Здесь нажмите 'ПОДКЛЮЧИТЬСЯ К NOKIA' и выберите устройство.");
        layout.addView(tvInstructions);
        
        btnConnect = new Button(this);
        btnConnect.setText("ПОДКЛЮЧИТЬСЯ К NOKIA");
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPermissionsAndConnect();
            }
        });
        layout.addView(btnConnect);
        
        btnCopyLogs = new Button(this);
        btnCopyLogs.setText("КОПИРОВАТЬ ЛОГИ");
        btnCopyLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("BT Logs", tvLogs.getText()));
                Toast.makeText(MainActivity.this, "Скопировано!", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(btnCopyLogs);
        
        scrollLogs = new ScrollView(this);
        tvLogs = new TextView(this);
        tvLogs.setTextSize(12);
        tvLogs.setText("Ожидание...\n");
        scrollLogs.addView(tvLogs);
        layout.addView(scrollLogs, new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            
        setContentView(layout);
        handler = new Handler(Looper.getMainLooper());
    }

    private void checkPermissionsAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                log("Запрос разрешений...");
                requestPermissions(new String[]{
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN
                }, PERMISSION_REQUEST_CODE);
                return;
            }
        }
        showDevicePicker();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("Разрешения получены.");
                showDevicePicker();
            } else {
                log("ОШИБКА: Нет разрешения Bluetooth!");
            }
        }
    }

    private void showDevicePicker() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("Ошибка: Bluetooth выключен");
            return;
        }

        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        if (pairedDevices.isEmpty()) {
            log("Ошибка: Нет сопряженных устройств. Сопрягите Nokia в настройках!");
            return;
        }

        final String[] deviceNames = new String[pairedDevices.size()];
        final BluetoothDevice[] devices = new BluetoothDevice[pairedDevices.size()];
        int i = 0;
        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName() != null ? device.getName() : device.getAddress();
            deviceNames[i] = name + " (" + device.getAddress() + ")";
            devices[i] = device;
            i++;
        }

        new AlertDialog.Builder(this)
            .setTitle("Выберите Nokia")
            .setItems(deviceNames, (dialog, which) -> {
                log("Подключение к: " + deviceNames[which]);
                new Thread(() -> connectToDevice(devices[which])).start();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void connectToDevice(BluetoothDevice device) {
        BluetoothSocket socket = null;
        try {
            log("Создание сокета через SDP (SPP UUID)...");
            // ПРАВИЛЬНЫЙ API: без reflection, использует SDP для поиска канала
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            
            log("Вызов connect()... (это может занять несколько секунд)");
            // Отменяем discovery, если оно идет, чтобы ускорить connect()
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
            socket.connect();
            log("*** СОЕДИНЕНИЕ УСТАНОВЛЕНО! ***");

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Читаем URL-запрос, который прислала Nokia
            log("Ожидание запроса от Nokia...");
            StringBuilder sb = new StringBuilder();
            int b;
            int crlfCount = 0;
            while ((b = in.read()) != -1) {
                char c = (char) b;
                sb.append(c);
                if (c == '\n') {
                    crlfCount++;
                    if (crlfCount >= 2) break;
                } else if (c != '\r') {
                    crlfCount = 0;
                }
            }

            String request = sb.toString().trim();
            log("Получен запрос: " + request.substring(0, Math.min(request.length(), 80)));

            if (request.startsWith("GET http")) {
                String[] tokens = request.split(" ");
                if (tokens.length >= 2) {
                    String targetUrl = tokens[1];
                    log("Fetching URL: " + targetUrl);

                    URL url = new URL(targetUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    
                    int responseCode = connection.getResponseCode();
                    log("Internet response code: " + responseCode);

                    // Отправляем полные HTTP-заголовки обратно на Nokia
                    String statusLine = "HTTP/1.1 " + responseCode + " " + connection.getResponseMessage() + "\r\n";
                    out.write(statusLine.getBytes("UTF-8"));
                    
                    String contentType = connection.getContentType();
                    if (contentType != null) {
                        out.write(("Content-Type: " + contentType + "\r\n").getBytes("UTF-8"));
                    }
                    
                    int contentLength = connection.getContentLength();
                    if (contentLength >= 0) {
                        out.write(("Content-Length: " + contentLength + "\r\n").getBytes("UTF-8"));
                    }
                    out.write("Connection: close\r\n\r\n".getBytes("UTF-8"));
                    out.flush();

                    // Корректная обработка потоков для 4xx/5xx ошибок
                    InputStream internetIn = (responseCode >= 400) ? connection.getErrorStream() : connection.getInputStream();
                    if (internetIn == null) {
                        internetIn = connection.getErrorStream();
                        if (internetIn == null) internetIn = new ByteArrayInputStream(new byte[0]);
                    }

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    int total = 0;
                    
                    while ((bytesRead = internetIn.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        out.flush();
                        total += bytesRead;
                    }
                    log("Отправлено на Nokia: " + total + " байт реальных данных");
                    internetIn.close();
                }
            }
        } catch (Exception e) {
            log("ОШИБКА: " + e.toString());
            e.printStackTrace();
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception e) {}
            log("Соединение закрыто");
        }
    }

    private void log(final String msg) {
        handler.post(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvLogs.append("[" + time + "] " + msg + "\n");
            scrollLogs.fullScroll(View.FOCUS_DOWN);
        });
    }
}
