package com.zcode.ideaplugin.cua;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 桌面操作执行（java.awt.Robot 封装）。
 *
 * 坐标系定案（2026-10-10 双探针实测，scripts/hidpi-probe/）：JBR JVM 为 per-monitor
 * DPI-aware，截图（createScreenCapture）与鼠标（mouseMove/MouseInfo）同处 AWT 逻辑
 * 空间，AI 在截图上读到的像素即点击坐标，全链路零 DPI 换算。PowerShell/Win32 来源
 * 的坐标（system-DPI 空间）与 AWT 空间不一致，禁止混入——进程枚举只返回 pid/
 * 名称/标题，不返回矩形（CuaApps）。
 */
final class CuaRobot {

    static final class CuaException extends RuntimeException {
        final String code;

        CuaException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final Robot ROBOT = createRobot();
    private static final GraphicsDevice[] DEVICES =
        GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();

    private CuaRobot() {
    }

    private static Robot createRobot() {
        try {
            return new Robot();
        } catch (java.awt.AWTException e) {
            throw new IllegalStateException("Robot unavailable: " + e.getMessage(), e);
        }
    }

    /** 屏幕摘要文本（报错信息用）。 */
    private static String screensSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DEVICES.length; i++) {
            Rectangle b = DEVICES[i].getDefaultConfiguration().getBounds();
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(b.x).append(',').append(b.y).append(' ')
                .append(b.width).append('x').append(b.height);
        }
        return sb.toString();
    }

    static int screenCount() {
        return DEVICES.length;
    }

    /** 校验坐标落在虚拟桌面范围内（越界=AI 用了陈旧/虚构栅格，fail-closed）。 */
    static void requireOnScreen(int x, int y) {
        for (GraphicsDevice gd : DEVICES) {
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            if (x >= b.x && x < b.x + b.width && y >= b.y && y < b.y + b.height) {
                return;
            }
        }
        throw new CuaException("ELEMENT_UNAVAILABLE",
            "coordinate (" + x + ", " + y + ") is outside all screens [" + screensSummary()
                + "]; use pixels from the latest screenshot");
    }

    /** 截取指定屏，返回 PNG 字节；displayIndex 越界报错。 */
    static byte[] captureScreen(int displayIndex) {
        if (displayIndex < 0 || displayIndex >= DEVICES.length) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < DEVICES.length; i++) {
                Rectangle b = DEVICES[i].getDefaultConfiguration().getBounds();
                if (i > 0) {
                    names.append("; ");
                }
                names.append(i).append(':').append(b.width).append('x').append(b.height);
            }
            throw new CuaException("ELEMENT_UNAVAILABLE",
                "display " + displayIndex + " not found; available: " + names);
        }
        Rectangle bounds = DEVICES[displayIndex].getDefaultConfiguration().getBounds();
        BufferedImage img = ROBOT.createScreenCapture(bounds);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new CuaException("INTERNAL", "png encode failed: " + e.getMessage());
        }
    }

    static Rectangle screenBounds(int displayIndex) {
        return DEVICES[displayIndex].getDefaultConfiguration().getBounds();
    }

    /**
     * 最近一次缩放截图的换算台账：AI 的坐标语义是「最新返回图像的像素」，真实派发
     * 需要乘回屏幕坐标（对齐官方 SKILL："CUA owns every transform from the returned
     * raster to native dispatch"）。null = 本会话尚未截图，坐标按屏幕原生像素直用。
     */
    static final class CaptureLedger {
        final int displayIndex;
        final int imgWidth;
        final int imgHeight;
        final Rectangle screenBounds;
        final String stateId;

        CaptureLedger(int displayIndex, int imgWidth, int imgHeight, Rectangle screenBounds, String stateId) {
            this.displayIndex = displayIndex;
            this.imgWidth = imgWidth;
            this.imgHeight = imgHeight;
            this.screenBounds = screenBounds;
            this.stateId = stateId;
        }

        /** 图像像素 → 屏幕坐标。 */
        int[] toScreen(int x, int y) {
            int sx = screenBounds.x + (int) Math.round(x * screenBounds.width / (double) imgWidth);
            int sy = screenBounds.y + (int) Math.round(y * screenBounds.height / (double) imgHeight);
            return new int[]{sx, sy};
        }
    }

    private static volatile CaptureLedger lastCapture;

    static CaptureLedger lastCapture() {
        return lastCapture;
    }

    static void setLastCapture(CaptureLedger ledger) {
        lastCapture = ledger;
    }

    /**
     * 截取指定屏并压缩为 JPEG，base64 预算内（app-server 通用 MCP 图片 inline 上限
     * 200KiB，超限落 artifact 模型就看不到图——e2e 实测）。缩放上限 1600px（Provider
     * 模型图片上限 2000px 内）；质量 0.8 起步逐级降。返回 {jpeg, 图宽, 图高}。
     */
    static Object[] captureScreenScaled(int displayIndex) {
        if (displayIndex < 0 || displayIndex >= DEVICES.length) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < DEVICES.length; i++) {
                Rectangle b = DEVICES[i].getDefaultConfiguration().getBounds();
                if (i > 0) {
                    names.append("; ");
                }
                names.append(i).append(':').append(b.width).append('x').append(b.height);
            }
            throw new CuaException("ELEMENT_UNAVAILABLE",
                "display " + displayIndex + " not found; available: " + names);
        }
        Rectangle bounds = DEVICES[displayIndex].getDefaultConfiguration().getBounds();
        BufferedImage raw = ROBOT.createScreenCapture(bounds);
        int maxDim = 1600;
        double scale = Math.min(1.0, maxDim / (double) Math.max(raw.getWidth(), raw.getHeight()));
        int w = Math.max(1, (int) Math.round(raw.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(raw.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(raw, 0, 0, w, h, null);
        g.dispose();
        byte[] best = null;
        for (float q : new float[]{0.8f, 0.6f, 0.45f, 0.3f}) {
            byte[] jpeg = encodeJpeg(scaled, q);
            // base64 膨胀 4/3；190_000 字符 ≈ 142KB JPEG，留 200KiB 预算的余量
            if (Base64.getEncoder().encodeToString(jpeg).length() <= 190_000 || q == 0.3f) {
                best = jpeg;
                break;
            }
        }
        return new Object[]{best, w, h};
    }

    private static byte[] encodeJpeg(BufferedImage img, float quality) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(128 * 1024);
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            var ios = ImageIO.createImageOutputStream(bos);
            writer.setOutput(ios);
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            ios.flush();
            ios.close();
            writer.dispose();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new CuaException("INTERNAL", "jpeg encode failed: " + e.getMessage());
        }
    }

    private static int buttonMask(String mouseButton) {
        if (mouseButton == null || mouseButton.isEmpty() || mouseButton.equalsIgnoreCase("left")
            || mouseButton.equalsIgnoreCase("l")) {
            return InputEvent.BUTTON1_DOWN_MASK;
        }
        if (mouseButton.equalsIgnoreCase("right") || mouseButton.equalsIgnoreCase("r")) {
            return InputEvent.BUTTON3_DOWN_MASK;
        }
        if (mouseButton.equalsIgnoreCase("middle") || mouseButton.equalsIgnoreCase("m")) {
            return InputEvent.BUTTON2_DOWN_MASK;
        }
        throw new CuaException("INTERNAL",
            "mouse_button must be left/right/middle (got " + mouseButton + ")");
    }

    static void click(int x, int y, String mouseButton, int clickCount) {
        requireOnScreen(x, y);
        int mask = buttonMask(mouseButton);
        ROBOT.mouseMove(x, y);
        int n = Math.max(1, Math.min(3, clickCount));
        for (int i = 0; i < n; i++) {
            ROBOT.mousePress(mask);
            ROBOT.mouseRelease(mask);
            sleep(60);
        }
    }

    static void drag(int fromX, int fromY, int toX, int toY, String mouseButton) {
        requireOnScreen(fromX, fromY);
        requireOnScreen(toX, toY);
        int mask = buttonMask(mouseButton);
        ROBOT.mouseMove(fromX, fromY);
        sleep(120);
        ROBOT.mousePress(mask);
        sleep(120);
        // 10 步插值（browser-use drag 同款）
        int steps = 10;
        for (int i = 1; i <= steps; i++) {
            ROBOT.mouseMove(fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps);
            sleep(30);
        }
        sleep(120);
        ROBOT.mouseRelease(mask);
    }

    static void scroll(int x, int y, String direction, int pages) {
        requireOnScreen(x, y);
        String dir = direction.toLowerCase();
        int amt;
        boolean horizontal;
        switch (dir) {
            case "up": case "u": amt = -pages; horizontal = false; break;
            case "down": case "d": amt = pages; horizontal = false; break;
            case "left": case "l": amt = -pages; horizontal = true; break;
            case "right": case "r": amt = pages; horizontal = true; break;
            default: throw new CuaException("INTERNAL",
                "scroll_direction must be up/down/left/right (got " + direction + ")");
        }
        ROBOT.mouseMove(x, y);
        sleep(80);
        if (horizontal) {
            // AWT mouseWheel 只有纵向；横向经 shift+纵滚近似（多数应用 shift+滚=横向）
            ROBOT.keyPress(KeyEvent.VK_SHIFT);
            ROBOT.mouseWheel(amt);
            ROBOT.keyRelease(KeyEvent.VK_SHIFT);
        } else {
            ROBOT.mouseWheel(amt);
        }
    }

    /** 键入文本：剪贴板 + Ctrl+V（Unicode 可靠路径），先备份后恢复剪贴板。 */
    static void typeText(String text) {
        Clipboard clip = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable old = null;
        try {
            old = clip.getContents(null);
        } catch (Exception ignore) {
            // 剪贴板被别的进程占用：备份失败不致命，继续
        }
        clip.setContents(new StringSelection(text), null);
        try {
            ROBOT.keyPress(KeyEvent.VK_CONTROL);
            ROBOT.keyPress(KeyEvent.VK_V);
            ROBOT.keyRelease(KeyEvent.VK_V);
            ROBOT.keyRelease(KeyEvent.VK_CONTROL);
        } finally {
            sleep(150);
            if (old != null) {
                try {
                    clip.setContents(old, null);
                } catch (Exception ignore) {
                    // 恢复失败不致命
                }
            }
        }
    }

    static void paste(String text) {
        typeText(text);
    }

    /**
     * 按下 chord（修饰键先按、主键最后），holdMs &gt; 0 时整体按住指定时长再松开。
     * 调用方（Main）为单线程事件循环，Robot 操作天然串行。
     */
    static void pressChord(List<Integer> codes, long holdMs) {
        List<Integer> pressed = new ArrayList<>(codes.size());
        try {
            for (int vk : codes) {
                ROBOT.keyPress(vk);
                pressed.add(vk);
            }
            sleep(holdMs > 0 ? Math.min(holdMs, 8000) : 40);
        } finally {
            for (int i = pressed.size() - 1; i >= 0; i--) {
                try {
                    ROBOT.keyRelease(pressed.get(i));
                } catch (Exception ignore) {
                    // 释放失败不中断（避免修饰键卡死更糟的只是跳过）
                }
            }
        }
    }

    static int[] pointer() {
        java.awt.Point p = MouseInfo.getPointerInfo().getLocation();
        return new int[]{p.x, p.y};
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
