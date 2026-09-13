package dev.lain.claudejb.mcp;

import dev.lain.claudejb.mcp.ToonHeader.Field;
import dev.lain.claudejb.mcp.ToonLines.Line;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToonTables {

    private final ToonCursor cursor;
    private final boolean strict;

    ToonTables(ToonCursor cursor, boolean strict) {
        this.cursor = cursor;
        this.strict = strict;
    }

    List<Object> tabular(ToonHeader header, int depth) {
        List<Object> rows = new ArrayList<>();
        boolean started = false;
        while (true) {
            cursor.skipBlanks();
            Line line = cursor.peek();
            if (line == null || line.depth != depth + 1 || !isRow(line.content, header.delimiter)) break;
            cursor.next();
            if (!started) {
                started = true;
                cursor.enterSpan(depth);
            }
            rows.add(row(header.fields, ToonScan.split(line.content, header.delimiter), header.leafCount()));
        }
        if (started) cursor.leaveSpan();
        if (strict && rows.size() != header.length) {
            throw new ToonException("expected " + header.length + " rows, found " + rows.size());
        }
        return rows;
    }

    Map<String, Object> keyed(ToonHeader header, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean started = false;
        int count = 0;
        while (true) {
            cursor.skipBlanks();
            Line line = cursor.peek();
            if (line == null || line.depth <= depth) break;
            if (line.depth != depth + 1) {
                if (strict) throw new ToonException("line " + line.number + " is over-indented for an entry row");
                cursor.next();
                continue;
            }
            cursor.next();
            int colon = ToonScan.indexOfUnquoted(line.content, ':');
            if (colon < 0) {
                if (strict) throw new ToonException("entry row without a colon at line " + line.number);
                continue;
            }
            if (!started) {
                started = true;
                cursor.enterSpan(depth);
            }
            String rest = ToonScan.trimSpaces(line.content.substring(colon + 1));
            List<String> cells = rest.isEmpty() ? new ArrayList<>() : ToonScan.split(rest, header.delimiter);
            String key = ToonText.keyOf(ToonScan.trimSpaces(line.content.substring(0, colon)));
            put(out, key, row(header.fields, cells, header.leafCount()), strict);
            count++;
        }
        if (started) cursor.leaveSpan();
        if (strict && count != header.length) throw new ToonException("expected " + header.length + " entries, found " + count);
        return out;
    }

    static void put(Map<String, Object> into, String key, Object value, boolean strict) {
        if (strict && into.containsKey(key)) throw new ToonException("duplicate key " + key);
        into.put(key, value);
    }

    private static boolean isRow(String content, char delimiter) {
        int colon = ToonScan.indexOfUnquoted(content, ':');
        int split = ToonScan.indexOfUnquoted(content, delimiter);
        return colon < 0 || (split >= 0 && split < colon);
    }

    private Map<String, Object> row(List<Field> fields, List<String> cells, int leafCount) {
        if (strict && cells.size() != leafCount) throw new ToonException("expected " + leafCount + " cells, found " + cells.size());
        return build(fields, cells.iterator());
    }

    private Map<String, Object> build(List<Field> fields, Iterator<String> cells) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field field : fields) {
            if (field.children != null) out.put(field.name, build(field.children, cells));
            else if (cells.hasNext()) out.put(field.name, ToonText.value(cells.next()));
        }
        return out;
    }
}
