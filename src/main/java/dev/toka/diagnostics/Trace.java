package dev.toka.diagnostics;

import java.time.Instant;
import java.util.*;

/** Independent bounded channels preserve lifecycle evidence during packet bursts. */
final class Trace {
    final long deadline;
    private final long started = System.nanoTime();
    private final Map<String, Ring> channels = new LinkedHashMap<>();
    private final Map<String, Stats> totals = new TreeMap<>();
    private final Map<Long, Map<String, Long>> seconds = new TreeMap<>();
    private long lastIn, lastOut;
    private static final class Ring {
        final int cap; long dropped; final ArrayDeque<String> rows = new ArrayDeque<>();
        Ring(int cap) { this.cap=cap; }
        void add(String row) { if(rows.size()==cap) {rows.removeFirst();dropped++;} rows.addLast(row); }
    }
    private static final class Stats { long count,cancelled,bytes,maxBytes; }
    Trace(int capacity, long deadline) {
        this.deadline=deadline;
        channels.put("lifecycle",new Ring(512));
        channels.put("incoming",new Ring(512));
        channels.put("blocks",new Ring(capacity));
        channels.put("block-entities",new Ring(512));
        channels.put("chunks",new Ring(512));
        channels.put("other-outgoing",new Ring(256));
        channels.put("first-packets",new Ring(64));
    }
    static String clean(String text) {
        if(text==null)return "";
        String safe=text.replaceAll("[\\p{Cntrl}]", " ");
        return safe.substring(0,Math.min(1024,safe.length()));
    }
    private String row(String text) { return Instant.now()+"\telapsedMs="+((System.nanoTime()-started)/1_000_000)+"\t"+clean(text); }
    synchronized void add(String text) { channels.get("lifecycle").add(row(text)); }
    synchronized void packet(String direction,String type,boolean cancelled,long bytes,String detail) {
        String key=direction+" "+type;
        if(!totals.containsKey(key) && totals.size()>=256) key=direction+" OTHER_TYPES";
        Stats stats=totals.computeIfAbsent(key,k->new Stats());
        stats.count++;if(cancelled)stats.cancelled++;stats.bytes+=Math.max(0,bytes);stats.maxBytes=Math.max(stats.maxBytes,bytes);
        long elapsed=(System.nanoTime()-started)/1_000_000;
        if(direction.equals("IN"))lastIn=elapsed;else lastOut=elapsed;
        long second=elapsed/1000;
        Map<String,Long> bucket=seconds.computeIfAbsent(second,k->new TreeMap<>());
        String bucketKey=bucket.containsKey(key)||bucket.size()<32?key:"OTHER_TYPES";
        bucket.merge(bucketKey,1L,Long::sum);
        if(seconds.size()>301) seconds.remove(seconds.keySet().iterator().next());
        String line=row(key+" cancelled="+cancelled+detail);
        Ring first=channels.get("first-packets");if(first.rows.size()<first.cap)first.add(line);
        String channel=direction.equals("IN")?"incoming":type.equals("LevelChunkPacket")?"chunks":
            type.equals("UpdateBlockPacket")?"blocks":type.equals("BlockEntityDataPacket")?"block-entities":"other-outgoing";
        channels.get(channel).add(line);
        if(type.equals("LoginPacket")||type.equals("RequestNetworkSettingsPacket")||type.equals("StartGamePacket")||
           type.equals("SetLocalPlayerAsInitializedPacket")||type.equals("DisconnectPacket")) channels.get("lifecycle").add(line);
    }
    synchronized String snapshot() {
        StringBuilder out=new StringBuilder("format=2\nlastIncomingElapsedMs="+lastIn+"\nlastOutgoingElapsedMs="+lastOut+"\n");
        out.append("\n[packet-totals]\ntype\tcount\tcancelled\tknownPayloadBytes\tmaxKnownPayloadBytes\n");
        totals.forEach((k,v)->out.append(k).append('\t').append(v.count).append('\t').append(v.cancelled).append('\t').append(v.bytes).append('\t').append(v.maxBytes).append('\n'));
        out.append("\n[per-second-counts]\nelapsedSecond\ttype\tcount\n");
        seconds.forEach((sec,counts)->counts.forEach((k,v)->out.append(sec).append('\t').append(k).append('\t').append(v).append('\n')));
        channels.forEach((name,ring)->{out.append("\n[").append(name).append("] dropped=").append(ring.dropped).append('\n');ring.rows.forEach(line->out.append(line).append('\n'));});
        return out.toString();
    }
}
