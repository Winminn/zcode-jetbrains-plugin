package com.zcode.ideaplugin.cua;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 键名/和弦 → Java VK 码序列。
 *
 * 兼容官方 CUA 的键名先验（xdotool / X keysym 写法）：{@code "a"}、{@code "Return"}、
 * {@code "Control_L+a"}、{@code "super+c"}、{@code "Up"}。本 server 仅 Windows：
 * super/cmd 落 Win 键，meta≈alt。
 */
final class CuaKeymap {

    private CuaKeymap() {
    }

    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        NAMED.put("ctrl", KeyEvent.VK_CONTROL); NAMED.put("control", KeyEvent.VK_CONTROL);
        NAMED.put("control_l", KeyEvent.VK_CONTROL); NAMED.put("control_r", KeyEvent.VK_CONTROL);
        NAMED.put("alt", KeyEvent.VK_ALT); NAMED.put("alt_l", KeyEvent.VK_ALT); NAMED.put("alt_r", KeyEvent.VK_ALT);
        NAMED.put("meta_l", KeyEvent.VK_ALT); NAMED.put("meta_r", KeyEvent.VK_ALT);
        NAMED.put("shift", KeyEvent.VK_SHIFT); NAMED.put("shift_l", KeyEvent.VK_SHIFT); NAMED.put("shift_r", KeyEvent.VK_SHIFT);
        NAMED.put("super", KeyEvent.VK_WINDOWS); NAMED.put("super_l", KeyEvent.VK_WINDOWS); NAMED.put("super_r", KeyEvent.VK_WINDOWS);
        NAMED.put("win", KeyEvent.VK_WINDOWS); NAMED.put("cmd", KeyEvent.VK_WINDOWS);
        NAMED.put("return", KeyEvent.VK_ENTER); NAMED.put("enter", KeyEvent.VK_ENTER); NAMED.put("kp_enter", KeyEvent.VK_ENTER);
        NAMED.put("esc", KeyEvent.VK_ESCAPE); NAMED.put("escape", KeyEvent.VK_ESCAPE);
        NAMED.put("tab", KeyEvent.VK_TAB);
        NAMED.put("space", KeyEvent.VK_SPACE);
        NAMED.put("backspace", KeyEvent.VK_BACK_SPACE);
        NAMED.put("delete", KeyEvent.VK_DELETE); NAMED.put("del", KeyEvent.VK_DELETE);
        NAMED.put("insert", KeyEvent.VK_INSERT);
        NAMED.put("home", KeyEvent.VK_HOME); NAMED.put("end", KeyEvent.VK_END);
        NAMED.put("pageup", KeyEvent.VK_PAGE_UP); NAMED.put("prior", KeyEvent.VK_PAGE_UP);
        NAMED.put("pagedown", KeyEvent.VK_PAGE_DOWN); NAMED.put("next", KeyEvent.VK_PAGE_DOWN);
        NAMED.put("up", KeyEvent.VK_UP); NAMED.put("down", KeyEvent.VK_DOWN);
        NAMED.put("left", KeyEvent.VK_LEFT); NAMED.put("right", KeyEvent.VK_RIGHT);
        NAMED.put("period", KeyEvent.VK_PERIOD); NAMED.put("comma", KeyEvent.VK_COMMA);
        NAMED.put("minus", KeyEvent.VK_MINUS); NAMED.put("equal", KeyEvent.VK_EQUALS);
        NAMED.put("slash", KeyEvent.VK_SLASH); NAMED.put("backslash", KeyEvent.VK_BACK_SLASH);
        NAMED.put("semicolon", KeyEvent.VK_SEMICOLON); NAMED.put("quote", KeyEvent.VK_QUOTE);
        NAMED.put("grave", KeyEvent.VK_BACK_QUOTE);
        NAMED.put("leftbrace", KeyEvent.VK_OPEN_BRACKET); NAMED.put("rightbrace", KeyEvent.VK_CLOSE_BRACKET);
        NAMED.put("capslock", KeyEvent.VK_CAPS_LOCK);
        NAMED.put("pause", KeyEvent.VK_PAUSE);
        NAMED.put("printscreen", KeyEvent.VK_PRINTSCREEN);
        NAMED.put("scrolllock", KeyEvent.VK_SCROLL_LOCK);
        NAMED.put("numlock", KeyEvent.VK_NUM_LOCK);
        for (int i = 1; i <= 24; i++) {
            // VK_F1..VK_F24 连续常量段（VK_F1=0x70）
            NAMED.put("f" + i, KeyEvent.VK_F1 + i - 1);
        }
        for (char c = 'a'; c <= 'z'; c++) {
            NAMED.put(String.valueOf(c), extendedKeyCode(c));
        }
        for (char c = '0'; c <= '9'; c++) {
            NAMED.put(String.valueOf(c), extendedKeyCode(c));
        }
    }

    private static int extendedKeyCode(char c) {
        int vk = KeyEvent.getExtendedKeyCodeForChar(c);
        return vk == KeyEvent.VK_UNDEFINED ? -1 : vk;
    }

    static boolean isModifier(int vk) {
        return vk == KeyEvent.VK_CONTROL || vk == KeyEvent.VK_ALT
            || vk == KeyEvent.VK_SHIFT || vk == KeyEvent.VK_WINDOWS || vk == KeyEvent.VK_META;
    }

    /**
     * 解析 chord（"+" 分隔）→ 按下顺序：修饰键在前、主键最后。
     * 返回 null 表示含未知键名（fail-closed 报 ACTION_UNAVAILABLE）。
     */
    static List<Integer> resolveChord(String chord) {
        List<Integer> codes = new ArrayList<>();
        List<Integer> mods = new ArrayList<>();
        for (String raw : chord.split("\\+")) {
            String t = raw.trim().toLowerCase();
            if (t.isEmpty()) {
                continue;
            }
            Integer vk = namedOrChar(t);
            if (vk == null || vk < 0) {
                return null;
            }
            if (isModifier(vk)) {
                mods.add(vk);
            } else {
                codes.add(vk);
            }
        }
        if (mods.isEmpty() && codes.isEmpty()) {
            return null;
        }
        List<Integer> all = new ArrayList<>(mods.size() + codes.size());
        all.addAll(mods);
        all.addAll(codes);
        return all;
    }

    private static Integer namedOrChar(String token) {
        Integer named = NAMED.get(token);
        if (named != null) {
            return named;
        }
        if (token.length() == 1) {
            int vk = KeyEvent.getExtendedKeyCodeForChar(token.charAt(0));
            if (vk != KeyEvent.VK_UNDEFINED) {
                return vk;
            }
        }
        return null;
    }
}
