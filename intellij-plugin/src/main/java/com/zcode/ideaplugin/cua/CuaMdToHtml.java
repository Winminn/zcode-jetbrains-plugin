package com.zcode.ideaplugin.cua;

import java.util.ArrayList;
import java.util.List;

/**
 * 迷你 Markdown → HTML 片段（paste format=md 用）。
 *
 * 只覆盖对话粘贴的高频子集：标题/粗体/斜体/行内代码/围栏代码块/链接/无序有序列表/
 * 段落与换行。刻意不做表格/引用/嵌套——目标应用（飞书/邮件/文档）自身对 HTML 片段
 * 的消化能力有限，复杂结构交给它们自己的 markdown 粘贴能力（多数会优先取纯文本）。
 */
final class CuaMdToHtml {

    private CuaMdToHtml() {
    }

    static String convert(String md) {
        StringBuilder out = new StringBuilder(md.length() + 64);
        String[] lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        List<String> para = new ArrayList<>();
        boolean inCode = false;
        List<String> codeBuf = new ArrayList<>();
        List<String> listBuf = new ArrayList<>();
        String listTag = null;

        for (String raw : lines) {
            String line = raw;
            if (inCode) {
                if (line.trim().startsWith("```")) {
                    out.append("<pre><code>").append(escape(join(codeBuf))).append("</code></pre>");
                    codeBuf.clear();
                    inCode = false;
                } else {
                    codeBuf.add(raw);
                }
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                flushPara(out, para);
                flushList(out, listBuf, listTag);
                listTag = null;
                inCode = true;
                continue;
            }
            if (trimmed.isEmpty()) {
                flushPara(out, para);
                flushList(out, listBuf, listTag);
                listTag = null;
                continue;
            }
            // 标题（# 到 ######）
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && level < 6 && trimmed.charAt(level) == '#') {
                    level++;
                }
                if (level < trimmed.length() && trimmed.charAt(level) == ' ') {
                    flushPara(out, para);
                    flushList(out, listBuf, listTag);
                    listTag = null;
                    out.append("<h").append(level).append('>')
                        .append(inline(trimmed.substring(level + 1).trim()))
                        .append("</h").append(level).append('>');
                    continue;
                }
            }
            // 列表项（- / * / 1.）
            String bullet = null;
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                bullet = "ul";
            } else if (trimmed.length() > 2 && trimmed.charAt(0) >= '0' && trimmed.charAt(0) <= '9'
                && trimmed.charAt(1) == '.') {
                bullet = "ol";
            }
            if (bullet != null) {
                flushPara(out, para);
                if (listTag == null) {
                    flushList(out, listBuf, listTag);
                    listTag = bullet;
                } else if (!listTag.equals(bullet)) {
                    flushList(out, listBuf, listTag);
                    listTag = bullet;
                }
                String item = trimmed.startsWith("- ") || trimmed.startsWith("* ")
                    ? trimmed.substring(2) : trimmed.substring(2).trim();
                listBuf.add("<li>" + inline(item) + "</li>");
                continue;
            }
            // 普通段落行（列表中出现的散行归段落并先收列表）
            flushList(out, listBuf, listTag);
            listTag = null;
            para.add(inline(trimmed));
        }
        if (inCode) {
            out.append("<pre><code>").append(escape(join(codeBuf))).append("</code></pre>");
        }
        flushPara(out, para);
        flushList(out, listBuf, listTag);
        return out.toString();
    }

    private static String join(List<String> buf) {
        return String.join("\n", buf);
    }

    private static void flushPara(StringBuilder out, List<String> para) {
        if (para.isEmpty()) {
            return;
        }
        out.append("<p>").append(String.join("<br>", para)).append("</p>");
        para.clear();
    }

    private static void flushList(StringBuilder out, List<String> buf, String tag) {
        if (buf.isEmpty() || tag == null) {
            buf.clear();
            return;
        }
        out.append('<').append(tag).append('>').append(join(buf))
            .append("</").append(tag).append('>');
        buf.clear();
    }

    /** 行内元素：`code`、**bold**、*italic*、[text](url)。先转义再还原占位。 */
    private static String inline(String s) {
        String esc = escape(s);
        StringBuilder sb = new StringBuilder(esc.length() + 16);
        for (int i = 0; i < esc.length(); i++) {
            char c = esc.charAt(i);
            if (c == '`') {
                int end = esc.indexOf('`', i + 1);
                if (end > i) {
                    sb.append("<code>").append(esc, i + 1, end).append("</code>");
                    i = end;
                    continue;
                }
            }
            if (c == '*' && i + 1 < esc.length() && esc.charAt(i + 1) == '*') {
                int end = esc.indexOf("**", i + 2);
                if (end > i) {
                    sb.append("<b>").append(esc, i + 2, end).append("</b>");
                    i = end + 1;
                    continue;
                }
            }
            if (c == '*' && i + 1 < esc.length() && esc.charAt(i + 1) != ' ') {
                int end = esc.indexOf('*', i + 1);
                if (end > i + 1) {
                    sb.append("<i>").append(esc, i + 1, end).append("</i>");
                    i = end;
                    continue;
                }
            }
            if (c == '[') {
                int mid = esc.indexOf("](", i);
                int end = mid > 0 ? esc.indexOf(')', mid) : -1;
                if (mid > i && end > mid) {
                    sb.append("<a href=\"").append(esc, mid + 2, end).append("\">")
                        .append(esc, i + 1, mid).append("</a>");
                    i = end;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '\"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
