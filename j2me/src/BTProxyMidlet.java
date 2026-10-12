package btproxy;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.bluetooth.*;
import java.io.*;

public class BTProxyMidlet extends MIDlet implements CommandListener, DiscoveryListener {
    private Display display;
    private Form form;
    private TextField urlField;
    private Command exitCommand, goCommand;
    private StringItem resultItem;
    
    private LocalDevice localDevice;
    private DiscoveryAgent agent;
    private RemoteDevice remoteDevice;
    private String connectionURL = null;

    private static final UUID SPP_UUID = new UUID(0x1101); 
    private static final String BT_MAC = "00:11:22:33:44:55"; 

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        form = new Form("Bluetooth Internet Proxy");
        urlField = new TextField("URL:", "http://example.com", 100, TextField.ANY);
        resultItem = new StringItem("Статус:", "Инициализация...");
        goCommand = new Command("Загрузить", Command.OK, 1);
        exitCommand = new Command("Выход", Command.EXIT, 1);
        
        form.append(urlField);
        form.append(resultItem);
        form.addCommand(goCommand);
        form.addCommand(exitCommand);
        form.setCommandListener(this);
    }

    protected void startApp() { 
        display.setCurrent(form);
        initBluetooth();
    }
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCommand) {
            notifyDestroyed();
        } else if (c == goCommand) {
            if (connectionURL == null) {
                resultItem.setText("Ошибка: Bluetooth не инициализирован или сервис не найден.");
                return;
            }
            resultItem.setText("Подключение и загрузка...");
            Thread t = new Thread(new Runnable() {
                public void run() { connectAndFetch(); }
            });
            t.start();
        }
    }

    private void initBluetooth() {
        try {
            localDevice = LocalDevice.getLocalDevice();
            agent = localDevice.getDiscoveryAgent();
            remoteDevice = agent.getRemoteDevice(BT_MAC.replace(":", ""));
            resultItem.setText("Поиск сервиса...");
            
            int[] attrSet = { 0x0100 }; 
            agent.searchServices(attrSet, new UUID[] { SPP_UUID }, remoteDevice, this);
        } catch (Exception e) {
            resultItem.setText("Ошибка BT: " + e.getMessage());
        }
    }

    public void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {}
    public void inquiryCompleted(int discType) {}
    
    public void serviceSearchCompleted(int transID, int respCode) {
        if (connectionURL != null) return;
        
        if (respCode == DiscoveryListener.SERVICE_SEARCH_NO_RECORDS ||
            respCode == DiscoveryListener.SERVICE_SEARCH_DEVICE_NOT_REACHABLE ||
            respCode == DiscoveryListener.SERVICE_SEARCH_ERROR) {
            resultItem.setText("Сервис не найден. Проверьте MAC и сервер.");
        }
    }
    
    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
        if (servRecord.length > 0) {
            connectionURL = servRecord[0].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, true);
            resultItem.setText("Готово. Введите URL.");
        }
    }

    private void connectAndFetch() {
        StreamConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            String url = urlField.getString();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }

            conn = (StreamConnection) Connector.open(connectionURL);
            os = conn.openOutputStream();
            is = conn.openInputStream();

            String httpRequest = "GET " + url + " HTTP/1.1\r\n" +
                                 "Host: " + extractHost(url) + "\r\n" +
                                 "User-Agent: NokiaX2-00/J2ME\r\n" +
                                 "Connection: close\r\n\r\n";
            
            os.write(httpRequest.getBytes("UTF-8"));
            os.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int bytesRead;
            int totalBytes = 0;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                if (totalBytes > 2000) break;
            }
            
            String response = new String(baos.toByteArray(), "UTF-8");
            if (response.length() > 800) {
                response = response.substring(0, 800) + "\n\n... [ответ обрезан]";
            }
            resultItem.setText(response);

        } catch (Exception e) {
            resultItem.setText("Ошибка: " + e.getMessage());
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (os != null) os.close(); } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }

    private String extractHost(String url) {
        String h = url.replace("http://", "").replace("https://", "");
        int slash = h.indexOf('/');
        return slash > 0 ? h.substring(0, slash) : h;
    }
}
