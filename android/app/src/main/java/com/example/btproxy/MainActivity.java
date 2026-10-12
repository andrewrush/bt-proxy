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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import javax.net.ssl.SSLSocketFactory;

public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    private TextView tvInstructions, tvLogs;
    private Button btnStartStop, btnCopyLogs;
    private ScrollView scrollLogs;
    private Handler handler;
    
    private boolean isRunning = false;
    private BluetoothServerSocket serverSocket;
    private Thread serverThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        
        tvInstructions = new TextView(this);
        tvInstructions.setTextSize(16);
        tvInstructions.setText("ИНСТРУКЦИЯ (Legacy Mode):\n1. Сопрягите Nokia и этот телефон.\n2. На Nokia запустите поиск и выберите этот телефон.\n3. Нажмите СТАРТ ниже.");
        layout.addView(tvInstructions);
        
        btnStartStop = new Button(this);
        btnStartStop.setText("СТАРТ СЕРВЕРА");
        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRunning) stopServer();
                else startServerLegacy();
            }
        });
        layout.addView(btnStartStop);
        
        btnCopyLogs = new Button(this);
        btnCopyLogs.setText("КОПИРОВАТЬ ЛОГИ");
        btnCopyLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("BT Logs", tvLogs.getText());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(MainActivity.this, "Логи скопированы!", Toast.LENGTH_SHORT).show();
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

    private void startServerLegacy() {
        if (isRunning) return;
        
        // Простая проверка для очень старых Android (до 6.0 разрешения даются при установке)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                log("ОШИБКА: Нет разрешения Bluetooth. Дайте его в настройках.");
                return;
            }
        }

        isRunning = true;
        btnStartStop.setText("СТОП СЕРВЕРА");
        log("Сервер запускается (Legacy Mode)...");
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
                // Используем стандартный метод, который на старых Android отлично работает с J2ME
                serverSocket = BluetoothAdapter.getDefaultAdapter()
                        .listenUsingRfcommWithServiceRecord("BTProxy", SPP_UUID);
                
                while (isRunning) {
                    log("accept() - жду соединение...");
                    BluetoothSocket clientSocket = serverSocket.accept();
                    log("*** КЛИЕНТ ПОДКЛЮЧИЛСЯ! ***");
                    new Thread(new ClientHandler(clientSocket)).start();
                }
            } catch (Exception e) {
                if (isRunning) log("Ошибка сервера: " + e.getMessage());
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

                log("Читаю запрос...");
                byte[] buffer = new byte[4096];
                int bytesRead = in.read(buffer);
                
                if (bytesRead > 0) {
                    String request = new String(buffer, 0, bytesRead, "UTF-8");
                    log("Получен запрос: " + request.substring(0, Math.min(request.length(), 100)));
                    
                    if (request.startsWith("GET http")) {
                        String[] tokens = request.split(" ");
                        URL url = new URL(tokens[1]);
                        String host = url.getHost();
                        int port = url.getPort() != -1 ? url.getPort() : (url.getProtocol().equals("https") ? 443 : 80);
                        String path = url.getFile().isEmpty() ? "/" : url.getFile();

                        log("Цель: " + host + ":" + port + path);

                        Socket targetSocket = url.getProtocol().equals("https") ?
                            SSLSocketFactory.getDefault().createSocket(host, port) : new Socket(host, port);

                        OutputStream targetOut = targetSocket.getOutputStream();
                        InputStream targetIn = targetSocket.getInputStream();

                        targetOut.write(("GET " + path + " HTTP/1.1\r\n").getBytes("UTF-8"));
                        targetOut.write(("Host: " + host + "\r\n").getBytes("UTF-8"));
                        targetOut.write("Connection: close\r\n\r\n".getBytes("UTF-8"));
                        targetOut.flush();

                        byte[] respBuffer = new byte[4096];
                        int total = 0;
                        int read;
                        while ((read = targetIn.read(respBuffer)) != -1) {
                            out.write(respBuffer, 0, read);
                            out.flush();
                            total += read;
                        }
                        log("Отправлено клиенту: " + total + " байт");
                        targetSocket.close();
                    }
                } else {
                    log("Запрос не получен (соединение закрыто клиентом)");
                }
            } catch (Exception e) {
                log("Ошибка обработки: " + e.getMessage());
            } finally {
                try { clientSocket.close(); } catch (Exception e) {}
                log("Соединение закрыто");
            }
        }
    }

    private void log(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                tvLogs.append("[" + time + "] " + msg + "\n");
                scrollLogs.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
}
