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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
    private List<BluetoothServerSocket> serverSockets = new ArrayList<>();
    private List<Thread> serverThreads = new ArrayList<>();
    private static final int PERMISSION_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        
        tvInstructions = new TextView(this);
        tvInstructions.setTextSize(16);
        tvInstructions.setText("ИНСТРУКЦИЯ:\n1. Сопрягите устройства.\n2. Введите MAC этого телефона в J2ME.\n3. Нажмите СТАРТ ниже.\n4. В J2ME нажмите Connect.");
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
        log("Сервер запускается...");
        
        for (int ch = 6; ch <= 30; ch++) {
            final int channel = ch;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    startListenerOnChannel(channel);
                }
            });
            t.start();
            serverThreads.add(t);
        }
    }

    private void startListenerOnChannel(int channel) {
        BluetoothServerSocket socket = null;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            // SECURE MODE — синхронизирован с Nokia
            socket = adapter.listenUsingRfcommWithServiceRecord("BTProxy_" + channel, SPP_UUID);
            log("Канал " + channel + ": слушает (secure)");
            
            serverSockets.add(socket);
            
            while (isRunning) {
                BluetoothSocket clientSocket = socket.accept();
                log("*** КЛИЕНТ НА КАНАЛЕ " + channel + "! ***");
                new Thread(new ClientHandler(clientSocket, channel)).start();
            }
        } catch (Exception e) {
            if (isRunning) log("Канал " + channel + ": ошибка - " + e.getMessage());
        }
    }

    private void stopServer() {
        isRunning = false;
        btnStartStop.setText("СТАРТ СЕРВЕРА");
        log("Остановка...");
        for (BluetoothServerSocket s : serverSockets) {
            try { s.close(); } catch (Exception e) {}
        }
        serverSockets.clear();
        for (Thread t : serverThreads) {
            try { t.interrupt(); } catch (Exception e) {}
        }
        serverThreads.clear();
        log("Сервер остановлен.");
    }

    private class ClientHandler implements Runnable {
        private BluetoothSocket clientSocket;
        private int channel;
        public ClientHandler(BluetoothSocket socket, int ch) { 
            this.clientSocket = socket; 
            this.channel = ch;
        }

        public void run() {
            try {
                log("[" + channel + "] Получаю потоки...");
                InputStream in = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream();
                
                // ЧИТАЕМ СРАЗУ, без задержки!
                log("[" + channel + "] Читаю запрос...");
                byte[] buffer = new byte[4096];
                int bytesRead = in.read(buffer);
                
                if (bytesRead > 0) {
                    String request = new String(buffer, 0, bytesRead, "UTF-8");
                    log("[" + channel + "] Получен запрос (" + bytesRead + " байт): " + request.substring(0, Math.min(request.length(), 100)));
                    
                    if (request.startsWith("GET http")) {
                        String[] tokens = request.split(" ");
                        URL url = new URL(tokens[1]);
                        String host = url.getHost();
                        int port = url.getPort() != -1 ? url.getPort() : (url.getProtocol().equals("https") ? 443 : 80);
                        String path = url.getFile().isEmpty() ? "/" : url.getFile();

                        log("[" + channel + "] Цель: " + host + ":" + port + path);

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
                        log("[" + channel + "] Отправлено клиенту: " + total + " байт");
                        targetSocket.close();
                    } else {
                        log("[" + channel + "] Не HTTP-запрос");
                    }
                } else {
                    log("[" + channel + "] Запрос не получен");
                }
                
            } catch (Exception e) {
                log("[" + channel + "] Ошибка: " + e.getMessage());
            } finally {
                try { clientSocket.close(); } catch (Exception e) {}
                log("[" + channel + "] Соединение закрыто");
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
