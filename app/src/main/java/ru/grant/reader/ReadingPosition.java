package ru.grant.reader;

import java.io.IOException;
import java.io.Reader;

/** Keeps the same printed character when layout-only whitespace is repaired. */
final class ReadingPosition {
    private ReadingPosition() { }

    static int afterWhitespaceRepair(String before, String after, int offset) {
        if (offset <= 0 || after.isEmpty()) return 0;
        int printed = 0;
        for (int i = 0, end = Math.min(offset, before.length()); i < end; i++) {
            if (!space(before.charAt(i))) printed++;
        }
        return offsetForPrintedCount(after, printed);
    }

    static int afterWhitespaceRepair(Reader before, String after, int offset) throws IOException {
        if (offset <= 0 || after.isEmpty()) return 0;
        char[] buffer = new char[8192];
        int remaining = offset, printed = 0;
        while (remaining > 0) {
            int count = before.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) break;
            if (count == 0) continue;
            for (int i = 0; i < count; i++) if (!space(buffer[i])) printed++;
            remaining -= count;
        }
        return offsetForPrintedCount(after, printed);
    }

    private static int offsetForPrintedCount(String after, int printed) {
        int at = 0;
        while (at < after.length() && printed > 0) {
            if (!space(after.charAt(at))) printed--;
            at++;
        }
        while (at < after.length() && space(after.charAt(at))) at++;
        at = Math.min(at, after.length() - 1);
        if (at > 0 && Character.isLowSurrogate(after.charAt(at)) && Character.isHighSurrogate(after.charAt(at - 1))) at--;
        return at;
    }

    private static boolean space(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == '\uFEFF';
    }
}
