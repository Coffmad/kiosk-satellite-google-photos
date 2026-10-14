// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader. org.json exists on Android but not in the JVM tests, so the plugin carries its own. */
final class Json {
    private final String text;
    private int at;

    private Json(String text) { this.text = text; }

    /** Lists, maps, strings, doubles, booleans or null. Throws IllegalArgumentException on malformed input. */
    static Object parse(String text) {
        Json json = new Json(text);
        json.space();
        Object value = json.value();
        json.space();
        if (json.at != text.length()) throw json.error("Trailing data");
        return value;
    }

    /** Escape a string as a JSON or JavaScript string literal, including the quotes. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                // Keeps the literal safe inside an HTML <script> element.
                case '<': out.append("\\u003c"); break;
                case '>': out.append("\\u003e"); break;
                case '&': out.append("\\u0026"); break;
                default:
                    // U+2028 and U+2029 end lines in older JavaScript string literals.
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private Object value() {
        if (at >= text.length()) throw error("Unexpected end");
        char c = text.charAt(at);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
        if (text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
        if (text.startsWith("null", at)) { at += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (peek('}')) { at++; return map; }
        while (true) {
            space();
            if (!peek('"')) throw error("Expected key");
            String key = string();
            space();
            expect(':');
            space();
            map.put(key, value());
            space();
            if (peek(',')) { at++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (peek(']')) { at++; return list; }
        while (true) {
            space();
            list.add(value());
            space();
            if (peek(',')) { at++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw error("Unterminated string");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw error("Unterminated escape");
            char e = text.charAt(at++);
            switch (e) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (at + 4 > text.length()) throw error("Bad unicode escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException bad) {
                        throw error("Bad unicode escape");
                    }
                    at += 4;
                    break;
                default: out.append(e);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        if (start == at) throw error("Unexpected character");
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException bad) {
            throw error("Bad number");
        }
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private boolean peek(char c) { return at < text.length() && text.charAt(at) == c; }

    private void expect(char c) {
        if (!peek(c)) throw error("Expected '" + c + "'");
        at++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + at);
    }
}
