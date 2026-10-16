package dev.toka.diagnostics;
import org.junit.Test;
import static org.junit.Assert.*;
public class TraceTest {
 @Test public void packetStormPreservesLifecycleAndOtherChannels() {
  Trace t=new Trace(2,0);t.add("START");t.add("TELEPORT_EVENT from=A to=B");
  t.packet("IN","LoginPacket",false,0," protocol=2193");
  t.packet("OUT","LevelChunkPacket",false,100," chunk=1,2");
  t.packet("OUT","UpdateBlockPacket",false,0," runtime=123");
  for(int i=0;i<10000;i++)t.packet("OUT","BlockEntityDataPacket",false,16," xyz=1,2,3");
  t.add("QUIT");String s=t.snapshot();
  assertTrue(s.contains("TELEPORT_EVENT from=A to=B"));assertTrue(s.contains("protocol=2193"));
  assertTrue(s.contains("chunk=1,2"));assertTrue(s.contains("runtime=123"));
  assertTrue(s.contains("OUT BlockEntityDataPacket\t10000\t0\t160000\t16"));
  assertTrue(s.contains("[block-entities] dropped=9488"));assertTrue(s.contains("[lifecycle] dropped=0"));
  assertTrue(s.length()<300000);
 }
 @Test public void blockRingKeepsRecentEvidenceAndLifetimeTotals() {
  Trace t=new Trace(2,0);for(int i=0;i<70;i++)t.packet("OUT","UpdateBlockPacket",i%2==0,0," index="+i);
  String s=t.snapshot();assertTrue(s.contains("[blocks] dropped=68"));
  assertTrue(s.contains("index=69"));assertTrue(s.contains("OUT UpdateBlockPacket\t70\t35"));
 }
 @Test public void markersCannotInjectLines() {assertEquals("a b c",Trace.clean("a\nb\tc"));assertEquals(1024,Trace.clean("x".repeat(2000)).length());}
 @Test public void fingerprintIsBoundedAndDoesNotExposePayload() {
  byte[] bytes=new byte[100000];String initial=Main.payloadSummary(bytes);
  bytes[90000]=1;assertEquals(initial,Main.payloadSummary(bytes));
  bytes[0]=1;assertNotEquals(initial,Main.payloadSummary(bytes));
  assertTrue(initial.startsWith(" bytes=100000 crc32First4096="));
  assertTrue(Main.payloadSummary(null).contains("bytes=0"));
 }
}
