package dev.lain.claudejb.mcp;

import dev.lain.claudejb.mcp.ToonLines.Line;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToonDecoder {

    private static final String EMPTY_ARRAY = "[]";
    private static final Object NO_ROOT_FORM = new Object();

    private final boolean strict;
    private final ToonCursor cursor;
    private final ToonTables tables;

    ToonDecoder(String text, Toon.Options options) {
        this.strict = options.strict;
        this.cursor = new ToonCursor(ToonLines.of(text, options), strict);
        this.tables = new ToonTables(cursor, strict);
    }

    Object decode() {
        cursor.skipBlanks();
        Line first = cursor.peek();
        if (first == null) return new LinkedHashMap<String, Object>();
        Object root = rootForm(first);
        return root != NO_ROOT_FORM ? root : objectBody(0, new LinkedHashMap<>());
    }

    private Object rootForm(Line first) {
        if (first.depth != 0) return NO_ROOT_FORM;
        ToonHeader header = first.content.equals(EMPTY_ARRAY) ? null : ToonHeader.parse(first.content, strict);
        Object value;
        if (first.content.equals(EMPTY_ARRAY)) {
            cursor.next();
            value = new ArrayList<>();
        } else if (header != null && header.key == null) {
            cursor.next();
            value = headerValue(header, 0);
        } else if (header == null && ToonScan.indexOfUnquoted(first.content, ':') < 0) {
            return rootPrimitive(first);
        } else {
            return NO_ROOT_FORM;
        }
        cursor.skipBlanks();
        Line trailing = cursor.peek();
        if (trailing != null && strict) throw new ToonException("content after the root form at line " + trailing.number);
        return value;
    }

    private Object rootPrimitive(Line line) {
        cursor.next();
        cursor.skipBlanks();
        Line trailing = cursor.peek();
        if (trailing != null) {
            throw new ToonException("a scalar line is valid only alone at the root; line " + trailing.number + " follows one");
        }
        return ToonText.value(line.content);
    }

    private Map<String, Object> objectBody(int depth, Map<String, Object> into) {
        while (true) {
            cursor.skipBlanks();
            Line line = cursor.peek();
            if (line == null || line.depth < depth) break;
            if (line.depth > depth) {
                orphan(line);
                continue;
            }
            cursor.next();
            field(line.content, depth, into);
        }
        return into;
    }

    private void orphan(Line line) {
        if (strict || ToonScan.indexOfUnquoted(line.content, ':') < 0) {
            throw new ToonException("line " + line.number + " belongs to no scope");
        }
        cursor.next();
    }

    private void field(String content, int depth, Map<String, Object> into) {
        ToonHeader header = ToonHeader.parse(content, strict);
        if (header != null) {
            if (header.key == null) throw new ToonException("keyless header in object position: " + content);
            ToonTables.put(into, header.key, headerValue(header, depth), strict);
            return;
        }
        int colon = ToonScan.indexOfUnquoted(content, ':');
        if (colon < 0) throw new ToonException("missing colon in: " + content);
        String key = ToonText.keyOf(ToonScan.trimSpaces(content.substring(0, colon)));
        String rest = ToonScan.trimSpaces(content.substring(colon + 1));
        Object value;
        if (rest.isEmpty()) value = objectBody(depth + 1, new LinkedHashMap<>());
        else if (rest.equals(EMPTY_ARRAY)) value = new ArrayList<>();
        else value = ToonText.value(rest);
        ToonTables.put(into, key, value, strict);
    }

    private Object headerValue(ToonHeader header, int depth) {
        if (header.keyed) return tables.keyed(header, depth);
        if (header.fields != null) return tables.tabular(header, depth);
        if (!header.inline.isEmpty()) return inlineArray(header);
        return listItems(header, depth);
    }

    private List<Object> inlineArray(ToonHeader header) {
        List<Object> values = new ArrayList<>();
        for (String token : ToonScan.split(header.inline, header.delimiter)) values.add(ToonText.value(token));
        if (strict && values.size() != header.length) {
            throw new ToonException("expected " + header.length + " values, found " + values.size());
        }
        return values;
    }

    private List<Object> listItems(ToonHeader header, int depth) {
        List<Object> items = new ArrayList<>();
        boolean started = false;
        while (true) {
            cursor.skipBlanks();
            Line line = cursor.peek();
            if (line == null || line.depth != depth + 1) break;
            String body = ToonScan.listItemContent(line.content);
            if (body == null) break;
            cursor.next();
            if (!started) {
                started = true;
                cursor.enterSpan(depth);
            }
            items.add(listItem(body, depth + 1));
        }
        if (started) cursor.leaveSpan();
        if (strict && items.size() != header.length) {
            throw new ToonException("expected " + header.length + " items, found " + items.size());
        }
        return items;
    }

    private Object listItem(String body, int depth) {
        if (body.isEmpty()) return new LinkedHashMap<String, Object>();
        if (body.equals(EMPTY_ARRAY)) return new ArrayList<>();
        ToonHeader header = ToonHeader.parse(body, strict);
        if (header != null && header.key == null) {
            if (header.fields != null) throw new ToonException("a keyless fields-bearing header is valid only at the root: " + body);
            return headerValue(header, depth);
        }
        if (header == null && ToonScan.indexOfUnquoted(body, ':') < 0) return ToonText.value(body);
        Map<String, Object> into = new LinkedHashMap<>();
        field(body, depth + 1, into);
        return objectBody(depth + 1, into);
    }
}
