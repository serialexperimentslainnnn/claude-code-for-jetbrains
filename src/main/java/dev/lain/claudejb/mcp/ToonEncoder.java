package dev.lain.claudejb.mcp;

import dev.lain.claudejb.mcp.ToonHeader.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ToonEncoder {

    private final Toon.Options options;
    private final char delimiter;
    private final String symbol;
    private final List<String> lines = new ArrayList<>();

    ToonEncoder(Toon.Options options) {
        this.options = options;
        this.delimiter = options.delimiter;
        this.symbol = delimiter == ',' ? "" : String.valueOf(delimiter);
    }

    String encode(Object value) {
        if (value instanceof List) array("", (List<?>) value, 0);
        else if (value instanceof Map) {
            Map<String, ?> obj = cast(value);
            List<Field> columns = keyedColumns(obj);
            if (columns != null) keyed("", obj, columns, 0);
            else fields(obj, 0);
        } else lines.add(ToonText.primitive(value, delimiter));
        return String.join("\n", lines);
    }

    private void fields(Map<String, ?> obj, int depth) {
        for (Map.Entry<String, ?> entry : obj.entrySet()) field(ToonText.key(entry.getKey()), entry.getValue(), depth);
    }

    private void field(String name, Object value, int depth) {
        if (value instanceof List) array(name, (List<?>) value, depth);
        else if (value instanceof Map) object(name, cast(value), depth);
        else emit(depth, name + ": " + ToonText.primitive(value, delimiter));
    }

    private void object(String name, Map<String, ?> value, int depth) {
        List<Field> columns = keyedColumns(value);
        if (value.isEmpty()) emit(depth, name + ":");
        else if (columns != null) keyed(name, value, columns, depth);
        else {
            emit(depth, name + ":");
            fields(value, depth + 1);
        }
    }

    private void array(String name, List<?> arr, int depth) {
        List<Field> columns = !arr.isEmpty() && allObjects(arr) ? columns(arr) : null;
        if (arr.isEmpty()) emit(depth, name.isEmpty() ? "[]" : name + ": []");
        else if (allPrimitives(arr)) emit(depth, name + bracket(arr.size()) + ": " + inline(arr));
        else if (columns != null) {
            emit(depth, name + bracket(arr.size()) + "{" + ToonHeader.render(columns, delimiter) + "}:");
            for (Object row : arr) emit(depth + 1, String.join(String.valueOf(delimiter), cells(cast(row), columns)));
        } else {
            emit(depth, name + bracket(arr.size()) + ":");
            for (Object element : arr) item(element, depth + 1);
        }
    }

    private void keyed(String name, Map<String, ?> obj, List<Field> columns, int depth) {
        emit(depth, name + "[" + obj.size() + ":" + symbol + "]{" + ToonHeader.render(columns, delimiter) + "}:");
        for (Map.Entry<String, ?> entry : obj.entrySet()) {
            String cells = String.join(String.valueOf(delimiter), cells(cast(entry.getValue()), columns));
            emit(depth + 1, ToonText.key(entry.getKey()) + ": " + cells);
        }
    }

    private void item(Object element, int depth) {
        if (element instanceof List) arrayItem((List<?>) element, depth);
        else if (element instanceof Map) objectItem(cast(element), depth);
        else emit(depth, "- " + ToonText.primitive(element, delimiter));
    }

    private void arrayItem(List<?> element, int depth) {
        if (allPrimitives(element)) {
            String values = element.isEmpty() ? "" : " " + inline(element);
            emit(depth, "- " + bracket(element.size()) + ":" + values);
        } else {
            emit(depth, "- " + bracket(element.size()) + ":");
            for (Object nested : element) item(nested, depth + 1);
        }
    }

    private void objectItem(Map<String, ?> element, int depth) {
        if (element.isEmpty()) {
            emit(depth, "-");
            return;
        }
        int first = lines.size();
        fields(element, depth + 1);
        lines.set(first, indent(depth) + "- " + lines.get(first).substring(indent(depth + 1).length()));
    }

    private List<Field> columns(List<?> objects) {
        Set<String> keys = cast(objects.get(0)).keySet();
        if (keys.isEmpty()) return null;
        for (Object object : objects) if (!cast(object).keySet().equals(keys)) return null;
        List<Field> out = new ArrayList<>();
        for (String key : keys) {
            List<Object> values = new ArrayList<>();
            for (Object object : objects) values.add(cast(object).get(key));
            Field column = column(key, values);
            if (column == null) return null;
            out.add(column);
        }
        return out;
    }

    private Field column(String name, List<Object> values) {
        if (allPrimitives(values)) return new Field(name, null);
        if (!allObjects(values)) return null;
        List<Field> children = columns(values);
        return children == null ? null : new Field(name, children);
    }

    private List<Field> keyedColumns(Map<String, ?> obj) {
        if (obj.size() < 2 || !allObjects(obj.values())) return null;
        return columns(new ArrayList<>(obj.values()));
    }

    private List<String> cells(Map<String, ?> obj, List<Field> columns) {
        List<String> out = new ArrayList<>();
        for (Field column : columns) {
            Object value = obj.get(column.name);
            if (column.children == null) out.add(ToonText.primitive(value, delimiter));
            else out.addAll(cells(cast(value), column.children));
        }
        return out;
    }

    private String inline(List<?> arr) {
        List<String> out = new ArrayList<>();
        for (Object value : arr) out.add(ToonText.primitive(value, delimiter));
        return String.join(String.valueOf(delimiter), out);
    }

    private static boolean allPrimitives(Iterable<?> values) {
        for (Object value : values) if (!ToonText.isPrimitive(value)) return false;
        return true;
    }

    private static boolean allObjects(Iterable<?> values) {
        for (Object value : values) if (!(value instanceof Map)) return false;
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cast(Object value) {
        return (Map<String, ?>) value;
    }

    private String bracket(int size) {
        return "[" + size + symbol + "]";
    }

    private void emit(int depth, String text) {
        lines.add(indent(depth) + text);
    }

    private String indent(int depth) {
        return " ".repeat(depth * options.indentSize);
    }
}
