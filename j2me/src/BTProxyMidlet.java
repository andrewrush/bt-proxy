package btproxy;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;

public class BTProxyMidlet extends MIDlet implements CommandListener, Runnable {
    private Display display;
    private Form form;
    private TextField macField, urlField;
    private StringItem statusItem;
    private Command goCmd, exitCmd;
    private Thread worker;

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        form = new Form("BT Proxy");
        
        macField = new TextField("MAC:", "C464F23DCAD6", 12, TextField.ANY);
        urlField = new TextField("URL:", "http://example.com", 100, TextField.ANY);
        statusItem = new StringItem("Status:", "Ready");
        
        form.append(macField);
        form.append(urlField);
        form.append(statusItem);
        
        goCmd = new Command("Connect", Command.OK, 1);
        exitCmd = new Command("Exit", Command.EXIT, 1);
        
        form.addCommand(goCmd);
        form.addCommand(exitCmd);
        form.setCommandListener(this);
    }

    protected void startApp() {
        display.setCurrent(form);
    }
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            notifyDestroyed();
        } else if (c == goCmd) {
            statusItem.setText("Connecting...");
            worker = new Thread(this);
            worker.start();
        }
    }

    public void run() {
        StreamConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            String url = urlField.getString();
            if (url == null) url = "http://example.com";
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            
            String mac = macField.getString();
            if (mac == null) mac = "000000000000";
            mac = mac.trim().toUpperCase();
            
            String connStr = "btspp://" + mac + ":1;authenticate=false;encrypt=false;master=false";
            
            conn = (StreamConnection) Connector.open(connStr, Connector.READ_WRITE, true);
            statusItem.setText("Connected! Sending...");
            
            os = conn.openOutputStream();
            is = conn.openInputStream();
            
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
                statusItem.setText("OK:\n" + resp);
            } else {
                statusItem.setText("Empty response");
            }
        } catch (Throwable e) {
            String errMsg = e.toString();
            if (errMsg == null || errMsg.trim().length() == 0) {
                errMsg = e.getClass().getName();
            }
            statusItem.setText("Error: " + errMsg);
        } finally {
            try { if (is != null) is.close(); } catch (Throwable e) {}
            try { if (os != null) os.close(); } catch (Throwable e) {}
            try { if (conn != null) conn.close(); } catch (Throwable e) {}
        }
    }
}
