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
    private Command exitCommand, goCommand, saveCommand;
    private StringItem resultItem;
    
    private static final String RMS_NAME = "BTProxySettings";
    private static final int BT_CHANNEL = 1;
    private String savedMac = null;

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        exitCommand = new Command("Выход", Command.EXIT, 1);
        
        mainForm = new Form("Bluetooth Internet Proxy");
        urlField = new TextField("URL:", "http://example.com", 100, TextField.ANY);
        resultItem = new StringItem("Статус:", "Готово к работе");
        goCommand = new Command("Загрузить", Command.OK, 1);
        
        mainForm.append(urlField);
        mainForm.append(resultItem);
        mainForm.addCommand(goCommand);
        mainForm.addCommand(exitCommand);
        mainForm.setCommandListener(this);

        setupForm = new Form("Настройка Bluetooth");
        macField = new TextField("MAC сервера (12 hex):", "", 12, TextField.ANY);
        saveCommand = new Command("Сохранить", Command.OK, 1);
        
        setupForm.append("Узнайте MAC-адрес в Настройках Android (без двоеточий, например: AABBCCDDEEFF)");
        setupForm.append(macField);
        setupForm.addCommand(saveCommand);
        setupForm.addCommand(exitCommand);
        setupForm.setCommandListener(this);
    }

    protected void startApp() { 
        savedMac = loadMacFromRMS();
        if (savedMac == null || savedMac.length() != 12) {
            display.setCurrent(setupForm);
        } else {
            display.setCurrent(mainForm);
        }
    }
    
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCommand) {
            notifyDestroyed();
        } else if (c == saveCommand && d == setupForm) {
            String mac = macField.getString().trim().toUpperCase();
            // Java 1.3 совместимая проверка на hex-символы
            if (mac.length() == 12 && isHexString(mac)) {
                saveMacToRMS(mac);
                savedMac = mac;
                display.setCurrent(mainForm);
            } else {
                alert("Ошибка", "Введите ровно 12 шестнадцатеричных символов (0-9, A-F) без двоеточий.");
            }
        } else if (c == goCommand && d == mainForm) {
            resultItem.setText("Подключение и загрузка...");
            Thread t = new Thread(new Runnable() {
                public void run() { connectAndFetch(); }
            });
            t.start();
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

            String connUrl = "btspp://" + savedMac + ":" + BT_CHANNEL + 
                             ";authenticate=false;encrypt=false;master=true";
            
            conn = (StreamConnection) Connector.open(connUrl);
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
        String h = url;
        if (h.startsWith("http://")) h = h.substring(7);
        else if (h.startsWith("https://")) h = h.substring(8);
        int slash = h.indexOf('/');
        return slash > 0 ? h.substring(0, slash) : h;
    }

    // Java 1.3 совместимая проверка строки на hex-символы
    private boolean isHexString(String str) {
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

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
        } catch (Exception e) {
            // Игнорируем ошибки при первом запуске
        }
        return null;
    }

    private void saveMacToRMS(String mac) {
        try {
            RecordStore rs = RecordStore.openRecordStore(RMS_NAME, true);
            byte[] data = mac.getBytes("UTF-8");
            if (rs.getNumRecords() > 0) {
                rs.setRecord(1, data, 0, data.length);
            } else {
                rs.addRecord(data, 0, data.length);
            }
            rs.closeRecordStore();
        } catch (Exception e) {
            alert("Ошибка сохранения", "Не удалось сохранить: " + e.getMessage());
        }
    }

    private void alert(String title, String message) {
        Alert a = new Alert(title, message, null, AlertType.ERROR);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a);
    }
}
