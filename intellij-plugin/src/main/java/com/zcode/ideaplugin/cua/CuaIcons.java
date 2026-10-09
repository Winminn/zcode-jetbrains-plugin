package com.zcode.ideaplugin.cua;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * 应用图标 PNG 的内容寻址落盘（list_apps 图标链 server 侧出口）。
 *
 * 图标走「磁盘旁路 + 文本短 key」而不是 MCP 图片 content：MCP 图片会被 app-server
 * inline 进模型上下文（200KiB 预算内直接吃视觉 token），图标对模型是纯噪音；
 * 落盘后由 webview 经插件桥 op=getCuaIcon 按需取图渲染。
 *
 * key = PNG 字节 MD5 前 12 个 hex 字符（内容寻址：同应用同图标天然去重，跨 pid/
 * 跨会话稳定；文件已存在即跳过写）。只增不改、单文件几 KB，不做清理。
 *
 * public：server（java 包）写、插件面板（ui 包）读，两侧共用。
 */
public final class CuaIcons {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CuaIcons() {
    }

    public static File dir() {
        return new File(new File(System.getProperty("user.home"), ".zcode"), "cli/zcgui-cua-icons");
    }

    /** 落盘并返回 12 位 hex key；IO/摘要失败返回 null（调用方省略 icon 字段，fail-soft）。 */
    public static synchronized String store(byte[] png) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(png);
            StringBuilder key = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                key.append(HEX[(digest[i] >> 4) & 0xf]).append(HEX[digest[i] & 0xf]);
            }
            String k = key.toString();
            File f = new File(dir(), k + ".png");
            if (!f.isFile()) {
                File dir = dir();
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return null;
                }
                Files.write(f.toPath(), png);
            }
            return k;
        } catch (Exception e) {
            return null;
        }
    }

    /** key 合法形态（插件侧读取前同口径校验，防路径穿越）。 */
    public static boolean validKey(String key) {
        if (key == null || key.length() != 12) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    static byte[] load(String key) throws Exception {
        return Files.readAllBytes(new File(dir(), key + ".png").toPath());
    }
}
