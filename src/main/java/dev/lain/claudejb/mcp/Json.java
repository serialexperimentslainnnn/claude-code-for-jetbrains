package dev.lain.claudejb.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.skipWhitespace();
        if (json.at != text.length()) throw json.error("trailing content");
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) out.append("null");
        else if (value instanceof String) writeString((String) value, out);
        else if (value instanceof Boolean || value instanceof Number) out.append(value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : value.toString());
        else if (value instanceof List) writeArray((List<?>) value, out);
        else if (value instanceof Map) writeObject((Map<?, ?>) value, out);
        else throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
    }

    private static void writeArray(List<?> values, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) out.append(',');
            first = false;
            write(value, out);
        }
        out.append(']');
    }

    private static void writeObject(Map<?, ?> values, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(String.valueOf(entry.getKey()), out);
            out.append(':');
            write(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < ' ') out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }

    private Object value() {
        skipWhitespace();
        if (at >= text.length()) throw error("unexpected end of input");
        char c = text.charAt(at);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        if (text.startsWith("true", at)) return literal("true", Boolean.TRUE);
        if (text.startsWith("false", at)) return literal("false", Boolean.FALSE);
        if (text.startsWith("null", at)) return literal("null", null);
        throw error("unexpected character '" + c + "'");
    }

    private Object literal(String token, Object value) {
        at += token.length();
        return value;
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        at++;
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw error("expected a string key");
            String key = string();
            skipWhitespace();
            expect(':');
            out.put(key, value());
            skipWhitespace();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect('}');
            return out;
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        at++;
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            out.add(value());
            skipWhitespace();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect(']');
            return out;
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        at++;
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) break;
            char e = text.charAt(at++);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) throw error("truncated unicode escape");
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default: throw error("invalid escape '\\" + e + "'");
            }
        }
        throw error("unterminated string");
    }

    private BigDecimal number() {
        int start = at;
        if (peek() == '-') at++;
        while (at < text.length() && "0123456789.eE+-".indexOf(text.charAt(at)) >= 0) at++;
        try {
            return new BigDecimal(text.substring(start, at));
        } catch (NumberFormatException e) {
            throw error("malformed number");
        }
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) throw error("expected '" + c + "'");
        at++;
    }

    private void skipWhitespace() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON " + message + " at offset " + at);
    }
}
