package dev.lain.claudejb.mcp;

import java.util.ArrayList;
import java.util.List;

final class ToonLines {

    static final class Line {
        final int number;
        final int depth;
        final String content;

        Line(int number, int depth, String content) {
            this.number = number;
            this.depth = depth;
            this.content = content;
        }

        boolean blank() {
            return content.isEmpty();
        }
    }

    private ToonLines() {
    }

    static List<Line> of(String text, Toon.Options options) {
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        String[] raws = body.split("\n", -1);
        List<Line> lines = new ArrayList<>(raws.length);
        for (int i = 0; i < raws.length; i++) {
            String raw = raws[i];
            if (raw.endsWith("\r")) raw = raw.substring(0, raw.length() - 1);
            Line line = line(i + 1, stripTrailingSpaces(raw), options);
            if (line != null) lines.add(line);
        }
        return lines;
    }

    private static Line line(int number, String raw, Toon.Options options) {
        int spaces = 0;
        while (spaces < raw.length() && raw.charAt(spaces) == ' ') spaces++;
        if (spaces < raw.length() && raw.charAt(spaces) == '#') return null;
        if (raw.chars().allMatch(c -> c == ' ' || c == '\t')) return new Line(number, 0, "");
        int tabs = 0;
        while (spaces + tabs < raw.length() && raw.charAt(spaces + tabs) == '\t') tabs++;
        if (options.strict && tabs > 0) throw new ToonException("tab in the indentation of line " + number);
        if (options.strict && spaces % options.indentSize != 0) {
            throw new ToonException("indentation of line " + number + " is not a multiple of " + options.indentSize);
        }
        return new Line(number, spaces / options.indentSize + tabs, raw.substring(spaces + tabs));
    }

    private static String stripTrailingSpaces(String raw) {
        int end = raw.length();
        while (end > 0 && raw.charAt(end - 1) == ' ') end--;
        return raw.substring(0, end);
    }
}
