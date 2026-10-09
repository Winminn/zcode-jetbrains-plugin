import java.awt.*;

/**
 * HiDPI 探针：打印 Java 视角的屏幕几何与缩放，截全屏对拍。
 * 运行：java Probe.java C:/tmp/hidpi
 */
public class Probe {
    public static void main(String[] args) throws Exception {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle v = ge.getMaximumWindowBounds();
        System.out.println("maximumWindowBounds=" + v);
        for (GraphicsDevice gd : ge.getScreenDevices()) {
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            System.out.println("device=" + gd.getIDstring()
                    + " bounds=" + gc.getBounds()
                    + " transformScale=" + gc.getDefaultTransform().getScaleX()
                    + "," + gc.getDefaultTransform().getScaleY());
        }
        Robot robot = new Robot();
        // 虚拟屏全截：各设备分别截其 bounds
        int i = 0;
        for (GraphicsDevice gd : ge.getScreenDevices()) {
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            Image img = robot.createScreenCapture(b);
            System.out.println("capture[" + (i++) + "] requested=" + b.width + "x" + b.height
                    + " actual=" + img.getWidth(null) + "x" + img.getHeight(null));
        }
        System.out.println("screenSize=" + Toolkit.getDefaultToolkit().getScreenSize());
        System.out.println("dpi=" + Toolkit.getDefaultToolkit().getScreenResolution());
    }
}
