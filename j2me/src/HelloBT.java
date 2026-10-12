import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.bluetooth.*;

public class HelloBT extends MIDlet {
    private Form form;
    
    public HelloBT() {
        form = new Form("BT Test");
    }
    
    protected void startApp() {
        try {
            LocalDevice local = LocalDevice.getLocalDevice();
            form.append("BT OK!\nMAC: " + local.getBluetoothAddress());
        } catch (Exception e) {
            form.append("BT FAIL:\n" + e.toString());
        }
        Display.getDisplay(this).setCurrent(form);
    }
    
    protected void pauseApp() {}
    protected void destroyApp(boolean b) {}
}
