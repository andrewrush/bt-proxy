package com.example.btproxy;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.util.UUID;
import javax.net.ssl.SSLSocketFactory;

public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private TextView statusText;
    private Handler handler;
    private boolean isRunning = true;
    private BluetoothServerSocket serverSocket;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusText = new TextView(this);
        statusText.setText("Проверка разрешений...");
        setContentView(statusText);
        handler = new Handler();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN
                }, PERMISSION_REQUEST_CODE);
                return;
            }
        }
        startServerLogic();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServerLogic();
            } else {
                statusText.setText("Ошибка: Требуется разрешение Bluetooth.");
            }
        }
    }

    private void startServerLogic() {
        statusText.setText("Сервер запускается.\nВаш MAC-адрес Bluetooth можно узнать в:\nНастройки -> О телефоне -> Общая информация.");
        new Thread(new ServerRunnable()).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception e) {}
        }
    }

    private class ServerRunnable implements Runnable {
        public void run() {
            try {
                log("Ожидание подключения...");
                serverSocket = BluetoothAdapter.getDefaultAdapter()
                        .listenUsingRfcommWithServiceRecord("BTProxy", SPP_UUID);
                
                log("Сервер запущен.");
                
                while (isRunning) {
                    BluetoothSocket clientSocket = serverSocket.accept();
                    log("Клиент подключен!");
                    new Thread(new ClientHandler(clientSocket)).start();
                }
            } catch (Exception e) {
                if (isRunning) {
                    log("Ошибка сервера: " + e.getMessage());
                }
            }
        }
    }

    private class ClientHandler implements Runnable {
        private BluetoothSocket clientSocket;

        public ClientHandler(BluetoothSocket socket) {
            this.clientSocket = socket;
        }

        public void run() {
            try {
                InputStream in = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));

                String requestLine = reader.readLine();
                if (requestLine != null && requestLine.startsWith("GET http")) {
                    String[] tokens = requestLine.split(" ");
                    String fullUrl = tokens[1];
                    URL url = new URL(fullUrl);
                    
                    String host = url.getHost();
                    int port = url.getPort() != -1 ? url.getPort() : (url.getProtocol().equals("https") ? 443 : 80);
                    String path = url.getFile().isEmpty() ? "/" : url.getFile();

                    log("Проксирование: " + host + ":" + port);

                    Socket targetSocket;
                    if (url.getProtocol().equals("https")) {
                        targetSocket = SSLSocketFactory.getDefault().createSocket(host, port);
                    } else {
                        targetSocket = new Socket(host, port);
                    }

                    OutputStream targetOut = targetSocket.getOutputStream();
                    InputStream targetIn = targetSocket.getInputStream();

                    String newRequest = "GET " + path + " HTTP/1.1\r\n";
                    targetOut.write(newRequest.getBytes("UTF-8"));
                    
                    String headerLine;
                    while ((headerLine = reader.readLine()) != null && headerLine.trim().length() > 0) {
                        targetOut.write((headerLine + "\r\n").getBytes("UTF-8"));
                    }
                    targetOut.write("\r\n".getBytes("UTF-8"));
                    targetOut.flush();

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = targetIn.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        out.flush();
                    }
                    targetSocket.close();
                    log("Запрос выполнен успешно.");
                }
            } catch (Exception e) {
                log("Ошибка обработки: " + e.getMessage());
            } finally {
                try { clientSocket.close(); } catch (Exception e) {}
            }
        }
    }

    private void log(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                statusText.setText(msg);
            }
        });
    }
}
