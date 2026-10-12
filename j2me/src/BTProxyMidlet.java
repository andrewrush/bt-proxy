package btproxy;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.bluetooth.*;
import java.io.*;
import java.util.Vector;

public class BTProxyMidlet extends MIDlet implements CommandListener, DiscoveryListener {
    private Display display;
    private Form mainForm, manualForm;
    private List deviceList;
    private TextField urlField, macField;
    private StringItem resultItem;
    
    private Command exitCmd, goCmd, saveCmd, scanCmd, manualCmd, backCmd;
    
    private static final String RMS_NAME = "BTProxySettings";
    private static final int BT_CHANNEL = 1;
    private String savedMac = null;
    
    private Vector foundDevices = new Vector();
    private Vector foundNames = new Vector();

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        
        exitCmd = new Command("Выход", Command.EXIT, 1);
        goCmd = new Command("Загрузить", Command.OK, 1);
        saveCmd = new Command("Сохранить", Command.OK, 1);
        scanCmd = new Command("Найти устройства", Command.SCREEN, 1);
        manualCmd = new Command("Ввести вручную", Command.SCREEN, 2);
        backCmd = new Command("Назад", Command.BACK, 1);

        // Главный экран
        mainForm = new Form("Bluetooth Proxy");
        urlField = new TextField("URL:", "http://example.com", 100, TextField.ANY);
        resultItem = new StringItem("Сервер:", "не выбран");
        mainForm.append(urlField);
        mainForm.append(resultItem);
        mainForm.addCommand(goCmd);
        mainForm.addCommand(scanCmd);
        mainForm.addCommand(manualCmd);
        mainForm.addCommand(exitCmd);
        mainForm.setCommandListener(this);

        // Экран ручного ввода (fallback)
        manualForm = new Form("Ввод MAC");
        macField = new TextField("MAC (12 hex):", "", 12, TextField.ANY);
        manualForm.append(new StringItem("", "Пример: AABBCCDDEEFF\n(без двоеточий)"));
        manualForm.append(macField);
        manualForm.addCommand(saveCmd);
        manualForm.addCommand(backCmd);
        manualForm.setCommandListener(this);

        // Список найденных устройств
        deviceList = new List("Выберите сервер", List.IMPLICIT);
        deviceList.addCommand(saveCmd);
        deviceList.addCommand(backCmd);
        deviceList.setCommandListener(this);
    }

    protected void startApp() { 
        savedMac = loadMacFromRMS();
        updateServerInfo();
        display.setCurrent(mainForm);
    }
    
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            notifyDestroyed();
        } else if (c == goCmd && d == mainForm) {
            if (savedMac == null) {
                alert("Внимание", "Сначала выберите сервер (кнопка 'Найти устройства')");
                return;
            }
            resultItem.setText("Загрузка...");
            new Thread(new Runnable() {
                public void run() { connectAndFetch(); }
            }).start();
        } else if (c == scanCmd && d == mainForm) {
            startInquiry();
        } else if (c == manualCmd && d == mainForm) {
            macField.setString(savedMac != null ? savedMac : "");
            display.setCurrent(manualForm);
        } else if (c == saveCmd && d == manualForm) {
            String mac = macField.getString().trim().toUpperCase();
            if (mac.length() == 12 && isHexString(mac)) {
                saveMacToRMS(mac);
                savedMac = mac;
                updateServerInfo();
                display.setCurrent(mainForm);
            } else {
                alert("Ошибка", "Введите 12 hex-символов (0-9, A-F)");
            }
        } else if (c == saveCmd && d == deviceList) {
            int idx = deviceList.getSelectedIndex();
            if (idx >= 0 && idx < foundDevices.size()) {
                String mac = (String) foundDevices.elementAt(idx);
                saveMacToRMS(mac);
                savedMac = mac;
                updateServerInfo();
                display.setCurrent(mainForm);
            }
        } else if (c == backCmd) {
            display.setCurrent(mainForm);
        }
    }

    private void updateServerInfo() {
        if (savedMac != null) {
            resultItem.setText("MAC: " + savedMac);
        } else {
            resultItem.setText("не выбран");
        }
    }

    private void startInquiry() {
        foundDevices.removeAllElements();
        foundNames.removeAllElements();
        deviceList.deleteAll();
        deviceList.append("Поиск устройств...", null);
        display.setCurrent(deviceList);
        
        try {
            LocalDevice local = LocalDevice.getLocalDevice();
            DiscoveryAgent agent = local.getDiscoveryAgent();
            agent.startInquiry(DiscoveryAgent.GIAC, this);
        } catch (Exception e) {
            alert("Ошибка", "Не удалось запустить поиск: " + e.getMessage());
            display.setCurrent(mainForm);
        }
    }

    // --- DiscoveryListener callbacks ---
    public void deviceDiscovered(RemoteDevice dev, DeviceClass cod) {
        try {
            String addr = dev.getBluetoothAddress();
            String name = dev.getFriendlyName(false);
            if (name == null || name.length() == 0) name = addr;
            foundDevices.addElement(addr);
            foundNames.addElement(name);
        } catch (Exception e) {
            // игнорируем проблемные устройства
        }
    }

    public void inquiryCompleted(int discType) {
        deviceList.deleteAll();
        if (foundDevices.size() == 0) {
            deviceList.append("(устройств не найдено)", null);
        } else {
            for (int i = 0; i < foundNames.size(); i++) {
                deviceList.append((String) foundNames.elementAt(i), null);
            }
        }
    }

    public void servicesDiscovered(int t, ServiceRecord[] r) {}
    public void serviceSearchCompleted(int t, int r) {}

    // --- Сетевое подключение ---
    private void connectAndFetch() {
        StreamConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            String url = urlField.getString();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }

            String connUrl = "btspp://" + savedMac + ":" + BT_CHANNEL + 
                             ";authenticate=false;encrypt=false;master=true";
            
            conn = (StreamConnection) Connector.open(connUrl);
            os = conn.openOutputStream();
            is = conn.openInputStream();

            String req = "GET " + url + " HTTP/1.1\r\n" +
                         "Host: " + extractHost(url) + "\r\n" +
                         "User-Agent: NokiaX2-00/J2ME\r\n" +
                         "Connection: close\r\n\r\n";
            
            os.write(req.getBytes("UTF-8"));
            os.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n, total = 0;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
                total += n;
                if (total > 2000) break;
            }
            
            String resp = new String(baos.toByteArray(), "UTF-8");
            if (resp.length() > 800) {
                resp = resp.substring(0, 800) + "\n\n... [обрезан]";
            }
            resultItem.setText(resp);

        } catch (Exception e) {
            resultItem.setText("Ошибка: " + e.getMessage());
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (os != null) os.close(); } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }

    private String extractHost(String url) {
        String h = url;
        if (h.startsWith("http://")) h = h.substring(7);
        else if (h.startsWith("https://")) h = h.substring(8);
        int slash = h.indexOf('/');
        return slash > 0 ? h.substring(0, slash) : h;
    }

    private boolean isHexString(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    // --- RMS ---
    private String loadMacFromRMS() {
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_NAME, false);
            if (rs.getNumRecords() > 0) {
                byte[] data = rs.getRecord(1);
                String mac = new String(data, "UTF-8");
                rs.closeRecordStore();
                return mac;
            }
            rs.closeRecordStore();
        } catch (Exception e) {}
        return null;
    }

    private void saveMacToRMS(String mac) {
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_NAME, true);
            byte[] data = mac.getBytes("UTF-8");
            if (rs.getNumRecords() > 0) rs.setRecord(1, data, 0, data.length);
            else rs.addRecord(data, 0, data.length);
            rs.closeRecordStore();
        } catch (Exception e) {
            alert("Ошибка", "Не удалось сохранить: " + e.getMessage());
        }
    }

    private void alert(String title, String msg) {
        Alert a = new Alert(title, msg, null, AlertType.ERROR);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a);
    }
}
