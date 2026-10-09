import java.awt.*;

/** 点击空间探针：与 PowerShell SetCursorPos 对拍。 */
public class Probe2 {
    public static void main(String[] args) throws Exception {
        System.out.println("== java view ==");
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            System.out.println(gd.getIDstring() + " bounds=" + gc.getBounds()
                    + " scale=" + gc.getDefaultTransform().getScaleX());
        }
        Point p = MouseInfo.getPointerInfo().getLocation();
        System.out.println("pointer(java)=" + p.x + "," + p.y);
    }
}
