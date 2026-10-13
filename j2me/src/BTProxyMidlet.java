package btproxy;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;

public class BTProxyMidlet extends MIDlet implements CommandListener, Runnable {
    private Display display;
    private Form mainForm;
    private TextField urlField;
    private StringItem statusItem;
    private Command startCmd, exitCmd;
    private StreamConnectionNotifier notifier;
    private String currentStep = "Idle";

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        mainForm = new Form("BT Proxy Server");
        urlField = new TextField("URL:", "http://neverssl.com", 100, TextField.ANY);
        statusItem = new StringItem("Status:", "Press 'Start Server'");
        mainForm.append(urlField);
        mainForm.append(statusItem);
        
        startCmd = new Command("Start Server", Command.OK, 1);
        exitCmd = new Command("Exit", Command.EXIT, 1);
        mainForm.addCommand(startCmd);
        mainForm.addCommand(exitCmd);
        mainForm.setCommandListener(this);
    }

    protected void startApp() { display.setCurrent(mainForm); }
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {
        try { if (notifier != null) notifier.close(); } catch (Exception e) {}
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) notifyDestroyed();
        else if (c == startCmd) {
            currentStep = "Starting server...";
            statusItem.setText(currentStep);
            new Thread(this).start();
        }
    }

    public void run() {
        StreamConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            currentStep = "1. Opening notifier";
            statusItem.setText(currentStep);
            // ПРАВИЛЬНЫЙ URL: регистрирует сервис в SDP, чтобы Android мог его найти
            String url = "btspp://localhost:0000110100001000800000805F9B34FB;name=BTProxy;authenticate=false;encrypt=false";
            notifier = (StreamConnectionNotifier) Connector.open(url);
            
            currentStep = "2. Waiting for Android...";
            statusItem.setText(currentStep);
            
            // Бесконечный цикл для обработки множественных подключений
            while (true) {
                conn = notifier.acceptAndOpen();
                currentStep = "3. Android connected!";
                statusItem.setText(currentStep);
                
                try {
                    currentStep = "4. Opening streams";
                    is = conn.openInputStream();
                    os = conn.openOutputStream();

                    currentStep = "5. Reading request";
                    byte[] buf = new byte[4096];
                    int n = is.read(buf);
                    if (n > 0) {
                        String req = new String(buf, 0, n, "UTF-8");
                        currentStep = "6. Sending to Android...";
                        statusItem.setText(currentStep + "\nReq: " + req.substring(0, Math.min(req.length(), 30)));
                        
                        // Отправляем запрос на Android
                        os.write(req.getBytes("UTF-8"));
                        os.flush();

                        currentStep = "7. Waiting for response";
                        // Читаем ответ от Android
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] rbuf = new byte[2048];
                        int total = 0, r;
                        while ((r = is.read(rbuf)) != -1) {
                            baos.write(rbuf, 0, r);
                            total += r;
                            if (total > 8192) break;
                        }
                        
                        if (total > 0) {
                            String resp = new String(baos.toByteArray(), "UTF-8");
                            if (resp.length() > 400) resp = resp.substring(0, 400) + "\n...[cut]";
                            statusItem.setText("SUCCESS:\n" + resp);
                        } else {
                            statusItem.setText("Empty response");
                        }
                    }
                } catch (Throwable e) {
                    statusItem.setText("FAIL at step '" + currentStep + "':\n" + e.toString());
                } finally {
                    try { if (is != null) is.close(); } catch (Throwable e) {}
                    try { if (os != null) os.close(); } catch (Throwable e) {}
                    try { if (conn != null) conn.close(); } catch (Throwable e) {}
                    currentStep = "8. Closed. Waiting for next...";
                    statusItem.setText(currentStep);
                }
            }
        } catch (Throwable e) {
            statusItem.setText("FAIL at step '" + currentStep + "':\n" + e.toString());
        } finally {
            try { if (notifier != null) notifier.close(); } catch (Exception e) {}
        }
    }
}
