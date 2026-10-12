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
                log("Слушаю через insecure метод...");
                serverSocket = BluetoothAdapter.getDefaultAdapter()
                        .listenUsingInsecureRfcommWithServiceRecord("BTProxy", SPP_UUID);
                log("Сервер слушает...");
                
                while (isRunning) {
                    log("accept() - жду соединение...");
                    BluetoothSocket clientSocket = serverSocket.accept();
                    log("Клиент подключился! Запускаю обработчик...");
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
                log("Получаю InputStream...");
                InputStream in = clientSocket.getInputStream();
                log("Получаю OutputStream...");
                OutputStream out = clientSocket.getOutputStream();

                // УПРОЩЁННОЕ ЧТЕНИЕ: читаем до первого \n\n (конец заголовков)
                StringBuilder sb = new StringBuilder();
                int b;
                int newlineCount = 0;
                int bytesRead = 0;
                
                log("Начинаю чтение заголовков...");
                while ((b = in.read()) != -1 && bytesRead < 4096) {
                    char c = (char) b;
                    sb.append(c);
                    bytesRead++;
                    
                    if (c == '\n') {
                        newlineCount++;
                        if (newlineCount >= 2) {
                            log("Найден конец заголовков (\\n\\n)");
                            break;
                        }
                    } else if (c != '\r') {
                        newlineCount = 0;
                    }
                }

                String requestLine = sb.toString().trim();
                log("Получено (" + bytesRead + " байт): " + requestLine.substring(0, Math.min(requestLine.length(), 100)));

                if (requestLine.startsWith("GET http")) {
                    String[] tokens = requestLine.split(" ");
                    if (tokens.length < 2) {
                        log("Ошибка: неверный формат URL");
                        out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"));
                        out.flush();
                        return;
                    }
                    URL url = new URL(tokens[1]);
                    String host = url.getHost();
                    int port = url.getPort() != -1 ? url.getPort() : (url.getProtocol().equals("https") ? 443 : 80);
                    String path = url.getFile().isEmpty() ? "/" : url.getFile();

                    log("Цель: " + host + ":" + port + path);

                    Socket targetSocket = url.getProtocol().equals("https") ?
                        SSLSocketFactory.getDefault().createSocket(host, port) : new Socket(host, port);
                    log("Соединение с целью установлено");

                    OutputStream targetOut = targetSocket.getOutputStream();
                    InputStream targetIn = targetSocket.getInputStream();

                    targetOut.write(("GET " + path + " HTTP/1.1\r\n").getBytes("UTF-8"));
                    targetOut.write(("Host: " + host + "\r\n").getBytes("UTF-8"));
                    targetOut.write("User-Agent: BTProxyServer\r\n".getBytes("UTF-8"));
                    targetOut.write("Connection: close\r\n\r\n".getBytes("UTF-8"));
                    targetOut.flush();
                    log("Запрос отправлен цели, жду ответ...");

                    byte[] respBuffer = new byte[4096];
                    int total = 0;
                    int read;
                    while ((read = targetIn.read(respBuffer)) != -1) {
                        out.write(respBuffer, 0, read);
                        out.flush();
                        total += read;
                        log("Отправлено клиенту: " + total + " байт");
                    }
                    log("ВСЕГО отправлено байт клиенту: " + total);
                    targetSocket.close();
                } else {
                    log("Игнорирован не-HTTP запрос");
                    out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes("UTF-8"));
                    out.flush();
                }
            } catch (Exception e) {
                log("Ошибка обработки: " + e.getMessage());
                e.printStackTrace();
            } finally {
                try { clientSocket.close(); } catch (Exception e) {}
                log("Соединение с клиентом закрыто");
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
