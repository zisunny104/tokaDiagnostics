package dev.toka.diagnostics;
import org.junit.Test;
import static org.junit.Assert.*;
public class TraceTest {
    @Test public void ringKeepsRecentEvidence() { Trace t = new Trace(2, 0); t.add("old"); t.add("middle"); t.add("new"); String s=t.snapshot(); assertFalse(s.contains("\told")); assertTrue(s.contains("dropped=1")); assertTrue(s.contains("\tnew")); }
    @Test public void markersCannotInjectLines() { assertEquals("a b c", Trace.clean("a\nb\tc")); assertEquals(512, Trace.clean("x".repeat(800)).length()); }
}
