package btproxy;

import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.bluetooth.*;
import java.io.*;
import java.util.Vector;

public class BTProxyMidlet extends MIDlet implements CommandListener, DiscoveryListener, Runnable {
    private Display display;
    private Form mainForm;
    private List deviceList;
    private TextField urlField;
    private StringItem statusItem;
    
    private Command exitCmd, searchCmd, connectCmd, backCmd;
    
    private DiscoveryAgent agent;
    private Vector devices = new Vector();
    private Vector names = new Vector();
    private RemoteDevice selectedDevice;
    private String connectionURL;

    // ИСПРАВЛЕНИЕ 6: Короткий UUID надежнее на старых JVM
    private static final UUID[] SEARCH_UUIDS = { new UUID(0x1101) };
    // ИСПРАВЛЕНИЕ 5: Запрашиваем только ProtocolDescriptorList для скорости
    private static final int[] ATTR_SET = { 0x0100 };

    public BTProxyMidlet() {
        display = Display.getDisplay(this);
        
        exitCmd = new Command("Exit", Command.EXIT, 1);
        searchCmd = new Command("Find Android", Command.OK, 1);
        connectCmd = new Command("Connect", Command.OK, 1);
        backCmd = new Command("Back", Command.BACK, 1);

        mainForm = new Form("BT Proxy Client");
        urlField = new TextField("URL:", "http://neverssl.com", 100, TextField.ANY);
        statusItem = new StringItem("Status:", "Press 'Find Android'");
        mainForm.append(urlField);
        mainForm.append(statusItem);
        mainForm.addCommand(searchCmd);
        mainForm.addCommand(exitCmd);
        mainForm.setCommandListener(this);

        deviceList = new List("Select Android Device", List.IMPLICIT);
        deviceList.addCommand(connectCmd);
        deviceList.addCommand(backCmd);
        deviceList.setCommandListener(this);
    }

    protected void startApp() {
        try {
            LocalDevice local = LocalDevice.getLocalDevice();
            agent = local.getDiscoveryAgent();
        } catch (Throwable t) {
            statusItem.setText("BT Init Error: " + t.toString());
        }
        display.setCurrent(mainForm);
    }
    
    protected void pauseApp() {}
    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            notifyDestroyed();
        } else if (c == searchCmd && d == mainForm) {
            statusItem.setText("Scanning...");
            // ИСПРАВЛЕНИЕ 4: Безопасная очистка списка для S40
            while (deviceList.size() > 0) {
                deviceList.delete(0);
            }
            devices.removeAllElements();
            names.removeAllElements();
            try {
                agent.startInquiry(DiscoveryAgent.GIAC, this);
            } catch (Throwable t) {
                statusItem.setText("Inquiry Error: " + t.toString());
            }
        } else if (c == backCmd && d == deviceList) {
            display.setCurrent(mainForm);
        } else if (c == connectCmd && d == deviceList) {
            int idx = deviceList.getSelectedIndex();
            if (idx >= 0) {
                selectedDevice = (RemoteDevice) devices.elementAt(idx);
                statusItem.setText("Searching service on " + (String)names.elementAt(idx) + "...");
                display.setCurrent(mainForm);
                try {
                    agent.searchServices(ATTR_SET, SEARCH_UUIDS, selectedDevice, this);
                } catch (Throwable t) {
                    statusItem.setText("Search Error: " + t.toString());
                }
            }
        }
    }

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
            statusItem.setText("No devices found. Check BT.");
        } else {
            while (deviceList.size() > 0) deviceList.delete(0);
            for (int i = 0; i < names.size(); i++) {
                deviceList.append((String) names.elementAt(i), null);
            }
            display.setCurrent(deviceList);
        }
    }

    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
        if (servRecord != null && servRecord.length > 0) {
            connectionURL = servRecord[0].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            statusItem.setText("Service found! Connecting...");
            new Thread(this).start();
        } else {
            statusItem.setText("BTProxy service not found on device.");
        }
    }

    public void serviceSearchCompleted(int transID, int respCode) {
        if (respCode != DiscoveryListener.SERVICE_SEARCH_COMPLETED) {
            statusItem.setText("Service search failed: " + respCode);
        }
    }

    public void run() {
        StreamConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            conn = (StreamConnection) Connector.open(connectionURL);
            statusItem.setText("Connected! Sending URL...");
            
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
            
            statusItem.setText("Waiting for response...");
            
            // ИСПРАВЛЕНИЕ 1: Потоковое чтение до конца (-1) с лимитом памяти S40
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int total = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
                total += n;
                if (total > 8192) break; // Защита от переполнения памяти на S40
            }
            
            if (total > 0) {
                String resp = new String(baos.toByteArray(), "UTF-8");
                if (resp.length() > 400) resp = resp.substring(0, 400) + "\n...[cut]";
                statusItem.setText("SUCCESS:\n" + resp);
            } else {
                statusItem.setText("Empty response from Android");
            }
        } catch (Throwable e) {
            // КЛЮЧЕВОЕ: e.toString() покажет точную причину (например, таймаут или отказ в доступе)
            statusItem.setText("Error: " + e.toString());
        } finally {
            try { if (is != null) is.close(); } catch (Throwable e) {}
            try { if (os != null) os.close(); } catch (Throwable e) {}
            try { if (conn != null) conn.close(); } catch (Throwable e) {}
        }
    }
}
