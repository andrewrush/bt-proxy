package btproxy;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;

public class BTProxyMidlet extends MIDlet implements CommandListener {
    private Display display;
    private Form form;
    private TextField urlField;
    private Command exitCommand, goCommand;
    private StringItem resultItem;
    
    // ЗАМЕНИТЕ на реальный MAC-адрес вашего Android-устройства (без двоеточий)
    // и укажите правильный RFCOMM-канал (обычно 1, 2 или 3)
    private static final String BT_MAC = "001122334455";
    private static final int BT_CHANNEL = 1;

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        form = new Form("Bluetooth Internet Proxy");
        urlField = new TextField("URL:", "http://example.com", 100, TextField.ANY);
        resultItem = new StringItem("Статус:", "Готово к работе");
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
    }
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCommand) {
            notifyDestroyed();
        } else if (c == goCommand) {
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

            // Прямое подключение к известному MAC-адресу и каналу без Service Discovery
            String connUrl = "btspp://" + BT_MAC + ":" + BT_CHANNEL + 
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
        if (h.startsWith("http://")) {
            h = h.substring(7);
        } else if (h.startsWith("https://")) {
            h = h.substring(8);
        }
        int slash = h.indexOf('/');
        return slash > 0 ? h.substring(0, slash) : h;
    }
}
