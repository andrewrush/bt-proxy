package btproxy;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import java.io.*;

public class BTProxyMidlet extends MIDlet implements CommandListener {
    private Display display;
    private Form mainForm, setupForm;
    private TextField urlField, macField;
    private StringItem resultItem, hintItem;
    private Command exitCmd, goCmd, saveCmd;
    
    private static final String RMS_NAME = "BTProxySettings";
    private static final int BT_CHANNEL = 1;
    private String savedMac = null;

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        exitCmd = new Command("Выход", Command.EXIT, 1);
        goCmd = new Command("Загрузить", Command.OK, 1);
        saveCmd = new Command("Сохранить", Command.OK, 1);

        mainForm = new Form("Bluetooth Proxy");
        urlField = new TextField("URL:", "http://example.com", 100, TextField.ANY);
        resultItem = new StringItem("Сервер:", "не задан");
        mainForm.append(urlField);
        mainForm.append(resultItem);
        mainForm.addCommand(goCmd);
        mainForm.addCommand(new Command("Настроить", Command.SCREEN, 1));
        mainForm.addCommand(exitCmd);
        mainForm.setCommandListener(this);

        setupForm = new Form("Настройка MAC");
        hintItem = new StringItem("", "Введите MAC Android-телефона\n(12 символов, БЕЗ двоеточий)\nПример: AABBCCDDEEFF");
        macField = new TextField("", "", 12, TextField.ANY);
        setupForm.append(hintItem);
        setupForm.append(macField);
        setupForm.addCommand(saveCmd);
        setupForm.addCommand(new Command("Назад", Command.BACK, 1));
        setupForm.setCommandListener(this);
    }

    protected void startApp() { 
        savedMac = loadMacFromRMS();
        updateStatus();
        display.setCurrent(mainForm);
    }
    
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            notifyDestroyed();
        } else if (c.getCommandType() == Command.SCREEN && d == mainForm) {
            macField.setString(savedMac != null ? savedMac : "");
            display.setCurrent(setupForm);
        } else if (c == saveCmd && d == setupForm) {
            String mac = macField.getString().trim().toUpperCase();
            if (mac.length() == 12 && isHex(mac)) {
                saveMacToRMS(mac);
                savedMac = mac;
                updateStatus();
                display.setCurrent(mainForm);
            } else {
                Alert a = new Alert("Ошибка", "Нужно ровно 12 символов (0-9, A-F)", null, AlertType.ERROR);
                a.setTimeout(2000);
                display.setCurrent(a, setupForm);
            }
        } else if (c.getCommandType() == Command.BACK && d == setupForm) {
            display.setCurrent(mainForm);
        } else if (c == goCmd && d == mainForm) {
            if (savedMac == null) {
                Alert a = new Alert("Внимание", "Сначала нажмите 'Настроить' и введите MAC", null, AlertType.WARNING);
                a.setTimeout(2000);
                display.setCurrent(a, mainForm);
                return;
            }
            resultItem.setText("Загрузка...");
            new Thread(new Runnable() {
                public void run() { connectAndFetch(); }
            }).start();
        }
    }

    private void updateStatus() {
        resultItem.setText(savedMac != null ? "OK: " + savedMac : "не задан");
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

            // master=false часто стабильнее работает на S40 при подключении к Android-серверу
            String connUrl = "btspp://" + savedMac + ":" + BT_CHANNEL + 
                             ";authenticate=false;encrypt=false;master=false";
            
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

    private boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    private String loadMacFromRMS() {
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_NAME, false);
            if (rs.getNumRecords() > 0) {
                String mac = new String(rs.getRecord(1), "UTF-8");
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
        } catch (Exception e) {}
    }
}
