package dev.lain.claudejb.mcp;

import java.util.ArrayList;
import java.util.List;

final class ToonHeader {

    static final class Field {
        final String name;
        final List<Field> children;

        Field(String name, List<Field> children) {
            this.name = name;
            this.children = children;
        }
    }

    private static final String DELIMITERS = ",\t|";

    final String key;
    final int length;
    final char delimiter;
    final boolean keyed;
    final List<Field> fields;
    final String inline;

    private ToonHeader(String key, int length, char delimiter, boolean keyed, List<Field> fields, String inline) {
        this.key = key;
        this.length = length;
        this.delimiter = delimiter;
        this.keyed = keyed;
        this.fields = fields;
        this.inline = inline;
    }

    int leafCount() {
        return fields == null ? 0 : leaves(fields);
    }

    static ToonHeader parse(String content, boolean strict) {
        int open = ToonScan.indexOfUnquoted(content, '[');
        if (open < 0) return null;
        int colon = ToonScan.indexOfUnquoted(content, ':');
        if (colon >= 0 && colon < open) return null;
        String rawKey = content.substring(0, open);
        if (!rawKey.equals(ToonScan.trimSpaces(rawKey))) return reject(strict, "whitespace before the bracket segment in: " + content);
        int close = content.indexOf(']', open);
        if (close < 0) return reject(strict, "unterminated bracket segment in: " + content);
        String segment = content.substring(open + 1, close);
        int end = 0;
        while (end < segment.length() && segment.charAt(end) >= '0' && segment.charAt(end) <= '9') end++;
        if (end == 0 || (segment.charAt(0) == '0' && end > 1)) return reject(strict, "malformed bracket segment in: " + content);
        int length;
        try {
            length = Integer.parseInt(segment.substring(0, end));
        } catch (NumberFormatException e) {
            return reject(strict, "bracket length out of range in: " + content);
        }
        String rest = segment.substring(end);
        boolean keyed = rest.startsWith(":");
        if (keyed) rest = rest.substring(1);
        char delimiter;
        if (rest.isEmpty()) delimiter = ',';
        else if (rest.equals("\t")) delimiter = '\t';
        else if (rest.equals("|")) delimiter = '|';
        else return reject(strict, "malformed bracket segment in: " + content);
        int at = close + 1;
        List<Field> fields = null;
        if (at < content.length() && content.charAt(at) == '{') {
            int braceEnd = ToonScan.matchingBrace(content, at);
            if (braceEnd < 0) return reject(strict, "unmatched brace in the field list of: " + content);
            fields = fields(content.substring(at + 1, braceEnd), delimiter, strict);
            if (fields == null) return reject(strict, "malformed field list in: " + content);
            at = braceEnd + 1;
        }
        if (at >= content.length() || content.charAt(at) != ':') {
            return reject(strict, "content between the bracket segment and the colon in: " + content);
        }
        if (keyed && fields == null) return reject(strict, "keyed header without a field list: " + content);
        String inline = ToonScan.trimSpaces(content.substring(at + 1));
        if (fields != null && !inline.isEmpty()) return reject(strict, "inline content after a fields-bearing header: " + content);
        String key = rawKey.isEmpty() ? null : ToonText.keyOf(rawKey);
        return new ToonHeader(key, length, delimiter, keyed, fields, inline);
    }

    static String render(List<Field> fields, char delimiter) {
        StringBuilder out = new StringBuilder();
        for (Field field : fields) {
            if (out.length() > 0) out.append(delimiter);
            out.append(ToonText.key(field.name));
            if (field.children != null) out.append('{').append(render(field.children, delimiter)).append('}');
        }
        return out.toString();
    }

    static int leaves(List<Field> fields) {
        int count = 0;
        for (Field field : fields) count += field.children == null ? 1 : leaves(field.children);
        return count;
    }

    private static ToonHeader reject(boolean strict, String message) {
        if (strict) throw new ToonException(message);
        return null;
    }

    private static List<Field> fields(String text, char delimiter, boolean strict) {
        List<Field> out = new ArrayList<>();
        for (String entry : entries(text, delimiter)) {
            int brace = ToonScan.indexOfUnquoted(entry, '{');
            String raw = ToonScan.trimSpaces(brace < 0 ? entry : entry.substring(0, brace));
            if (raw.isEmpty() || mismatched(raw, delimiter)) return null;
            List<Field> children = null;
            if (brace >= 0) {
                int end = ToonScan.matchingBrace(entry, brace);
                if (end != entry.length() - 1) return null;
                children = fields(entry.substring(brace + 1, end), delimiter, strict);
                if (children == null) return null;
            }
            String name = ToonText.keyOf(raw);
            if (strict && out.stream().anyMatch(f -> f.name.equals(name))) {
                throw new ToonException("duplicate field name " + name + " in {" + text + "}");
            }
            out.add(new Field(name, children));
        }
        return out;
    }

    private static boolean mismatched(String name, char delimiter) {
        if (name.startsWith("\"")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (DELIMITERS.indexOf(c) >= 0 && c != delimiter) return true;
        }
        return false;
    }

    private static List<String> entries(String text, char delimiter) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted && c == '\\') i++;
            else if (c == '"') quoted = !quoted;
            else if (quoted) continue;
            else if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == delimiter && depth == 0) {
                out.add(ToonScan.trimSpaces(text.substring(start, i)));
                start = i + 1;
            }
        }
        out.add(ToonScan.trimSpaces(text.substring(start)));
        return out;
    }
}
