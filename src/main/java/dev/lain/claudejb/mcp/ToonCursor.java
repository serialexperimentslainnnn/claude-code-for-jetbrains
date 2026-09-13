package dev.lain.claudejb.mcp;

import dev.lain.claudejb.mcp.ToonLines.Line;
import java.util.ArrayList;
import java.util.List;

final class ToonCursor {

    private final List<Line> lines;
    private final boolean strict;
    private final List<Integer> spans = new ArrayList<>();
    private int index;

    ToonCursor(List<Line> lines, boolean strict) {
        this.lines = lines;
        this.strict = strict;
    }

    Line peek() {
        return index < lines.size() ? lines.get(index) : null;
    }

    Line next() {
        return lines.get(index++);
    }

    void skipBlanks() {
        boolean skipped = false;
        while (peek() != null && peek().blank()) {
            index++;
            skipped = true;
        }
        Line next = peek();
        if (next == null || spans.isEmpty()) return;
        if (skipped && strict && next.depth > spans.get(0)) {
            throw new ToonException("blank line inside the scope that continues at line " + next.number);
        }
    }

    void enterSpan(int depth) {
        spans.add(depth);
    }

    void leaveSpan() {
        spans.remove(spans.size() - 1);
    }
}
