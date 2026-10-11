package com.zcode.ideaplugin.cua;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析（RFC 8259 子集）与转义写出。
 *
 * CUA MCP server 进程为纯 Java 零依赖设计（-cp 仅插件自身 jar 一个元素，不带
 * kotlin-stdlib / IDE 平台库——2026.1 平台发行已无独立 stdlib jar，fork JVM 拿不到，
 * 故 server 主体整体用 Java）。parse 返回 Map/List/String/Double/Boolean/null，
 * 数字统一 Double。
 */
final class CuaMiniJson {

    private CuaMiniJson() {
    }

    static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos < text.length()) {
            throw new IllegalArgumentException("trailing chars at " + p.pos);
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{': return parseObj();
                case '[': return parseArr();
                case '"': return parseStr();
                case 't': return lit("true", Boolean.TRUE);
                case 'f': return lit("false", Boolean.FALSE);
                case 'n': return lit("null", null);
                default: return parseNum();
            }
        }

        Object lit(String word, Object v) {
            if (!s.startsWith(word, pos)) {
                throw new IllegalArgumentException("bad literal at " + pos);
            }
            pos += word.length();
            return v;
        }

        Map<String, Object> parseObj() {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String k = parseStr();
                skipWs();
                if (s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("expect : at " + pos);
                }
                pos++;
                skipWs();
                m.put(k, parseValue());
                skipWs();
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return m;
                } else {
                    throw new IllegalArgumentException("expect , or } at " + pos);
                }
            }
        }

        List<Object> parseArr() {
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(parseValue());
                skipWs();
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return l;
                } else {
                    throw new IllegalArgumentException("expect , or ] at " + pos);
                }
            }
        }

        String parseStr() {
            if (s.charAt(pos) != '"') {
                throw new IllegalArgumentException("expect string at " + pos);
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    pos++;
                    char e = s.charAt(pos);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos + 1, pos + 5), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape \\" + e + " at " + pos);
                    }
                    pos++;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
        }

        Double parseNum() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    static String str(String s) {
        return "\"" + escape(s) + "\"";
    }

    /** 已构造好的 JSON 文本 → text content block 的 result 信封。 */
    static String textResult(String jsonText, boolean isError) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + str(jsonText)
            + "}],\"isError\":" + isError + "}";
    }

    /** 带截图的 result 信封（base64 纯 ASCII 直接内嵌）。 */
    static String imageResult(String text, String base64, String mimeType) {
        return imageResult(text, base64, mimeType, false);
    }

    /** 带截图且可标记失败的 result 信封（batch 失败回执用：错误文本 + 现场截图）。 */
    static String imageResult(String text, String base64, String mimeType, boolean isError) {
        return "{\"content\":["
            + "{\"type\":\"text\",\"text\":" + str(text) + "},"
            + "{\"type\":\"image\",\"data\":\"" + base64 + "\",\"mimeType\":\"" + mimeType + "\"}"
            + "],\"isError\":" + isError + "}";
    }
}
