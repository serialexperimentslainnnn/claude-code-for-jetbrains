package dev.lain.claudejb.mcp;

import java.math.BigDecimal;
import java.util.regex.Pattern;

final class ToonText {

    private static final int HEX_DIGITS = 4;
    private static final String LEADING = " \t-#";
    private static final String TRAILING = " \t";
    private static final String STRUCTURAL = ":\"\\[]{}";
    private static final BigDecimal PLAIN_MIN = new BigDecimal("1e-6");
    private static final BigDecimal PLAIN_MAX = new BigDecimal("1e21");
    private static final Pattern UNQUOTED_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final Pattern NUMERIC_LIKE = Pattern.compile("[+-]?[0-9]+(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final Pattern LEADING_ZERO = Pattern.compile("^-?0[0-9]");

    private ToonText() {
    }

    static String key(String name) {
        requireScalars(name);
        return UNQUOTED_KEY.matcher(name).matches() ? name : quote(name);
    }

    static String primitive(Object value, char delimiter) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof String) return string((String) value, delimiter);
        if (value instanceof BigDecimal) return canonical((BigDecimal) value);
        if (value instanceof Number) return canonical(new BigDecimal(value.toString()));
        throw new ToonException("not a primitive: " + value.getClass().getName());
    }

    static boolean isPrimitive(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    static String string(String value, char delimiter) {
        requireScalars(value);
        return needsQuotes(value, delimiter) ? quote(value) : value;
    }

    static String canonical(BigDecimal number) {
        BigDecimal n = number.stripTrailingZeros();
        if (n.signum() == 0) return "0";
        BigDecimal magnitude = n.abs();
        if (magnitude.compareTo(PLAIN_MIN) >= 0 && magnitude.compareTo(PLAIN_MAX) < 0) return n.toPlainString();
        String digits = n.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - n.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        String sign = n.signum() < 0 ? "-" : "";
        String exponentSign = exponent < 0 ? "-" : "+";
        return sign + mantissa + "e" + exponentSign + Math.abs(exponent);
    }

    static Object value(String token) {
        if (token.startsWith("\"")) return unquote(token);
        if (token.equals("true")) return Boolean.TRUE;
        if (token.equals("false")) return Boolean.FALSE;
        if (token.equals("null")) return null;
        if (NUMBER.matcher(token).matches() && !LEADING_ZERO.matcher(token).find()) return new BigDecimal(token);
        return token;
    }

    static String keyOf(String token) {
        return token.startsWith("\"") ? unquote(token) : token;
    }

    static String unquote(String token) {
        StringBuilder out = new StringBuilder();
        int i = 1;
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c == '"') {
                if (i != token.length() - 1) throw new ToonException("characters after the closing quote in " + token);
                return out.toString();
            }
            if (c == '\\') i = escape(token, i + 1, out);
            else out.append(c);
            i++;
        }
        throw new ToonException("unterminated string: " + token);
    }

    private static int escape(String token, int at, StringBuilder out) {
        if (at >= token.length()) throw new ToonException("unterminated escape in " + token);
        char c = token.charAt(at);
        switch (c) {
            case '\\': out.append('\\'); return at;
            case '"': out.append('"'); return at;
            case 'n': out.append('\n'); return at;
            case 'r': out.append('\r'); return at;
            case 't': out.append('\t'); return at;
            case 'u': break;
            default: throw new ToonException("invalid escape in " + token);
        }
        int end = at + 1 + HEX_DIGITS;
        String hex = token.substring(at + 1, Math.min(end, token.length()));
        if (hex.length() != HEX_DIGITS || !hex.chars().allMatch(ToonText::isHex)) {
            throw new ToonException("truncated unicode escape in " + token);
        }
        int code = Integer.parseInt(hex, 16);
        if (Character.isSurrogate((char) code)) throw new ToonException("surrogate escape in " + token);
        out.append((char) code);
        return end - 1;
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '"') out.append("\\\"");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < ' ') out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static boolean needsQuotes(String s, char delimiter) {
        if (s.isEmpty() || s.equals("true") || s.equals("false") || s.equals("null")) return true;
        if (NUMERIC_LIKE.matcher(s).matches()) return true;
        if (LEADING.indexOf(s.charAt(0)) >= 0 || TRAILING.indexOf(s.charAt(s.length() - 1)) >= 0) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (STRUCTURAL.indexOf(c) >= 0 || c < ' ' || c == delimiter) return true;
        }
        return false;
    }

    private static void requireScalars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean paired = Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1));
            if (paired) i++;
            else if (Character.isSurrogate(c)) throw new ToonException("unpaired surrogate at index " + i);
        }
    }
}
