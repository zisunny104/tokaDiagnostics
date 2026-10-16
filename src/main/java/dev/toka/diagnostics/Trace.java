package dev.toka.diagnostics;

import java.time.Instant;
import java.util.ArrayDeque;

/** Fixed-size, thread-safe metadata buffer; payloads must never be passed here. */
final class Trace {
    final long deadline;
    private final int capacity;
    private final ArrayDeque<String> rows = new ArrayDeque<>();
    private long dropped;
    Trace(int capacity, long deadline) { this.capacity = capacity; this.deadline = deadline; }
    static String clean(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(512, text.length()));
    }
    synchronized void add(String text) {
        if (rows.size() == capacity) { rows.removeFirst(); dropped++; }
        rows.addLast(Instant.now() + "\t" + clean(text));
    }
    synchronized String snapshot() { return "dropped=" + dropped + "\n" + String.join("\n", rows) + "\n"; }
}
