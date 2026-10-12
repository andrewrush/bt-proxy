package btproxy;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.bluetooth.*;
import java.io.*;
import java.util.Vector;

public class BTProxyMidlet extends MIDlet implements CommandListener, DiscoveryListener, Runnable {
    private Display display;
    private Form mainForm, resultForm;
    private List deviceList;
    private TextField urlField;
    private StringItem statusItem;
    
    private Command exitCmd, searchCmd, connectCmd, backCmd;
    
    private DiscoveryAgent agent;
    private Vector devices = new Vector();
    private Vector names = new Vector();
    private RemoteDevice selectedDevice;
    private String connectionURL;

    // Стандартный SPP UUID, который использует наш Android-сервер
    private static final UUID[] SEARCH_UUIDS = { 
        new UUID("0000110100001000800000805F9B34FB", false) 
    };

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        
        exitCmd = new Command("Выход", Command.EXIT, 1);
        searchCmd = new Command("Найти сервер", Command.OK, 1);
        connectCmd = new Command("Подключиться", Command.OK, 1);
        backCmd = new Command("Назад", Command.BACK, 1);

        // Главный экран
        mainForm = new Form("BT Proxy");
        urlField = new TextField("URL:", "http://neverssl.com", 100, TextField.ANY);
        statusItem = new StringItem("Статус:", "Нажмите 'Найти сервер'");
        mainForm.append(urlField);
        mainForm.append(statusItem);
        mainForm.addCommand(searchCmd);
        mainForm.addCommand(exitCmd);
        mainForm.setCommandListener(this);

        // Список найденных устройств
        deviceList = new List("Выберите устройство", List.IMPLICIT);
        deviceList.addCommand(connectCmd);
        deviceList.addCommand(backCmd);
        deviceList.setCommandListener(this);
    }

    protected void startApp() {
        try {
            LocalDevice local = LocalDevice.getLocalDevice();
            agent = local.getDiscoveryAgent();
        } catch (BluetoothStateException e) {
            statusItem.setText("Ошибка BT: " + e.toString());
        }
        display.setCurrent(mainForm);
    }
    
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            notifyDestroyed();
        } else if (c == searchCmd && d == mainForm) {
            statusItem.setText("Поиск устройств...");
            devices.removeAllElements();
            names.removeAllElements();
            deviceList.deleteAll();
            try {
                agent.startInquiry(DiscoveryAgent.GIAC, this);
            } catch (BluetoothStateException e) {
                statusItem.setText("Ошибка: " + e.toString());
            }
        } else if (c == backCmd && d == deviceList) {
            display.setCurrent(mainForm);
        } else if (c == connectCmd && d == deviceList) {
            int idx = deviceList.getSelectedIndex();
            if (idx >= 0) {
                selectedDevice = (RemoteDevice) devices.elementAt(idx);
                statusItem.setText("Поиск сервиса на " + (String)names.elementAt(idx) + "...");
                display.setCurrent(mainForm); // Возвращаемся на главный экран с индикатором
                try {
                    agent.searchServices(null, SEARCH_UUIDS, selectedDevice, this);
                } catch (BluetoothStateException e) {
                    statusItem.setText("Ошибка поиска: " + e.toString());
                }
            }
        }
    }

    // --- DiscoveryListener: Поиск устройств ---
    public void deviceDiscovered(RemoteDevice btDevice, DeviceClass cod) {
        try {
            String name = btDevice.getFriendlyName(false);
            if (name == null || name.length() == 0) name = btDevice.getBluetoothAddress();
            devices.addElement(btDevice);
            names.addElement(name);
        } catch (Exception e) {}
    }

    public void inquiryCompleted(int discType) {
        if (devices.size() == 0) {
            statusItem.setText("Устройства не найдены");
        } else {
            deviceList.deleteAll();
            for (int i = 0; i < names.size(); i++) {
                deviceList.append((String) names.elementAt(i), null);
            }
            display.setCurrent(deviceList);
        }
    }

    // --- DiscoveryListener: Поиск сервисов ---
    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
        if (servRecord != null && servRecord.length > 0) {
            // Получаем готовую строку подключения от системы!
            connectionURL = servRecord[0].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            statusItem.setText("Сервис найден! Подключаюсь...");
            
            // Запускаем подключение в отдельном потоке
            Thread t = new Thread(this);
            t.start();
        } else {
            statusItem.setText("Сервис BTProxy не найден");
        }
    }

    public void serviceSearchCompleted(int transID, int respCode) {
        if (respCode != DiscoveryListener.SERVICE_SEARCH_COMPLETED) {
            statusItem.setText("Ошибка поиска сервиса: " + respCode);
        }
    }

    // --- Подключение и отправка данных ---
    public void run() {
        StreamConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            conn = (StreamConnection) Connector.open(connectionURL);
            statusItem.setText("Подключено! Отправка...");
            
            os = conn.openOutputStream();
            is = conn.openInputStream();
            
            String url = urlField.getString();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            
            String host = url;
            if (host.startsWith("http://")) host = host.substring(7);
            else if (host.startsWith("https://")) host = host.substring(8);
            int slash = host.indexOf('/');
            if (slash > 0) host = host.substring(0, slash);

            String req = "GET " + url + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: NokiaX2-00\r\nConnection: close\r\n\r\n";
            os.write(req.getBytes("UTF-8"));
            os.flush();
            
            byte[] buf = new byte[1024];
            int len = is.read(buf);
            if (len > 0) {
                String resp = new String(buf, 0, len);
                if (resp.length() > 400) resp = resp.substring(0, 400) + "\n...[cut]";
                statusItem.setText("УСПЕХ:\n" + resp);
            } else {
                statusItem.setText("Пустой ответ");
            }
        } catch (Throwable e) {
            statusItem.setText("Ошибка: " + e.toString());
        } finally {
            try { if (is != null) is.close(); } catch (Throwable e) {}
            try { if (os != null) os.close(); } catch (Throwable e) {}
            try { if (conn != null) conn.close(); } catch (Throwable e) {}
        }
    }
}
