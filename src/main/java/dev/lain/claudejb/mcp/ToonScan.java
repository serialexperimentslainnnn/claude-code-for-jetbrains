package dev.lain.claudejb.mcp;

import java.util.ArrayList;
import java.util.List;

final class ToonScan {

    private ToonScan() {
    }

    static int indexOfUnquoted(String text, char target) {
        return indexOfUnquoted(text, target, 0);
    }

    static int indexOfUnquoted(String text, char target, int from) {
        boolean quoted = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted && c == '\\') i++;
            else if (c == '"') quoted = !quoted;
            else if (!quoted && c == target) return i;
        }
        return -1;
    }

    static List<String> split(String text, char delimiter) {
        List<String> tokens = new ArrayList<>();
        int start = 0;
        while (true) {
            int at = indexOfUnquoted(text, delimiter, start);
            if (at < 0) break;
            tokens.add(trimSpaces(text.substring(start, at)));
            start = at + 1;
        }
        tokens.add(trimSpaces(text.substring(start)));
        return tokens;
    }

    static int matchingBrace(String text, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted && c == '\\') i++;
            else if (c == '"') quoted = !quoted;
            else if (quoted) continue;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    static String listItemContent(String content) {
        if (content.equals("-")) return "";
        if (content.startsWith("- ")) return trimSpaces(content.substring(2));
        return null;
    }

    static String trimSpaces(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && token.charAt(start) == ' ') start++;
        while (end > start && token.charAt(end - 1) == ' ') end--;
        return token.substring(start, end);
    }
}
