package com.example.btproxy;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
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
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    private TextView tvInstructions, tvLogs;
    private Button btnStartStop, btnCopyLogs;
    private ScrollView scrollLogs;
    private Handler handler;
    
    private boolean isRunning = false;
    private BluetoothServerSocket serverSocket;
    private Thread serverThread;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        
        tvInstructions = new TextView(this);
        tvInstructions.setTextSize(16);
        tvInstructions.setText("ИНСТРУКЦИЯ:\n1. Сопрягите Nokia и этот телефон в настройках Bluetooth.\n2. Нажмите СТАРТ СЕРВЕРА ниже.\n3. На Nokia запустите приложение и нажмите 'Find Android', затем 'Connect'.");
        layout.addView(tvInstructions);
        
        btnStartStop = new Button(this);
        btnStartStop.setText("СТАРТ СЕРВЕРА");
        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRunning) stopServer();
                else checkPermissionsAndStart();
            }
        });
        layout.addView(btnStartStop);
        
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

    private void checkPermissionsAndStart() {
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
        startServer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("Разрешения получены.");
                startServer();
            } else {
                log("ОШИБКА: Нет разрешения Bluetooth!");
            }
        }
    }

    private void startServer() {
        if (isRunning) return;
        isRunning = true;
        btnStartStop.setText("СТОП СЕРВЕРА");
        log("Сервер запускается (Real Proxy Mode)...");
        serverThread = new Thread(new ServerRunnable());
        serverThread.start();
    }

    private void stopServer() {
        isRunning = false;
        btnStartStop.setText("СТАРТ СЕРВЕРА");
        log("Остановка...");
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception e) {}
        }
        if (serverThread != null) {
            try { serverThread.interrupt(); } catch (Exception e) {}
        }
        log("Сервер остановлен.");
    }

    private class ServerRunnable implements Runnable {
        public void run() {
            try {
                log("Ожидание подключений...");
                serverSocket = BluetoothAdapter.getDefaultAdapter()
                        .listenUsingRfcommWithServiceRecord("BTProxy", SPP_UUID);
                
                while (isRunning) {
                    log("accept() - жду соединение от Nokia...");
                    BluetoothSocket clientSocket = serverSocket.accept();
                    log("*** NOKIA ПОДКЛЮЧИЛАСЬ! ***");
                    new Thread(new ClientHandler(clientSocket)).start();
                }
            } catch (Exception e) {
                if (isRunning) log("Ошибка сервера: " + e.toString());
            }
        }
    }

    private class ClientHandler implements Runnable {
        private BluetoothSocket clientSocket;
        public ClientHandler(BluetoothSocket socket) { this.clientSocket = socket; }

        public void run() {
            try {
                InputStream in = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream();

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

                        // ИСПРАВЛЕНИЕ 2: Отправляем полные HTTP-заголовки
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

                        // ИСПРАВЛЕНИЕ 3: Корректная обработка потоков для 4xx/5xx ошибок
                        InputStream internetIn;
                        if (responseCode >= 400) {
                            internetIn = connection.getErrorStream();
                        } else {
                            internetIn = connection.getInputStream();
                        }
                        if (internetIn == null) {
                            internetIn = connection.getErrorStream();
                            if (internetIn == null) {
                                internetIn = new ByteArrayInputStream(new byte[0]);
                            }
                        }

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        int total = 0;
                        
                        while ((bytesRead = internetIn.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                            out.flush();
                            total += bytesRead;
                        }
                        log("Отправлено Nokia: " + total + " байт реальных данных");
                        internetIn.close();
                    }
                }
            } catch (Exception e) {
                log("Ошибка обработки: " + e.toString());
            } finally {
                try { 
                    clientSocket.close();
                } catch (Exception e) {}
                log("Соединение с Nokia закрыто. Сервер продолжает слушать.");
            }
        }
    }

    private void log(final String msg) {
        handler.post(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvLogs.append("[" + time + "] " + msg + "\n");
            scrollLogs.fullScroll(View.FOCUS_DOWN);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
}
