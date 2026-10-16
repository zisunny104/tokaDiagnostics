package dev.toka.diagnostics;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.*;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerTeleportEvent;
import cn.nukkit.event.server.*;
import cn.nukkit.network.protocol.*;
import cn.nukkit.plugin.PluginBase;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class Main extends PluginBase implements Listener {
    private final Map<Player, Trace> traces = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Player, Boolean> completed = Collections.synchronizedMap(new IdentityHashMap<>());
    private synchronized Trace traceFor(Player player) {
        Trace current = traces.get(player);
        if (current != null) return current;
        if (traces.size() >= 2 || completed.containsKey(player)) return null;
        Trace trace = new Trace(2048, System.nanoTime() + TimeUnit.SECONDS.toNanos(120));
        trace.add("AUTO_START prelogin-capable");
        traces.put(player, trace);
        return trace;
    }
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), r -> { Thread t = new Thread(r, "tokaDiagnostics-export"); t.setDaemon(true); return t; });
    private final ThreadPoolExecutor validator = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), r -> { Thread t=new Thread(r,"tokaDiagnostics-validate");t.setDaemon(true);return t; });
    private Map<Player, Trace> activeTraces() { synchronized (traces) { return new IdentityHashMap<>(traces); } }
    private Path reports;
    private String environment;
    private String coreFingerprint;
    @Override public void onEnable() {
        reports = getDataFolder().toPath().resolve("reports");
        try { Files.createDirectories(reports); }
        catch (java.io.IOException e) {
            getLogger().error("無法建立診斷報告資料夾，插件停用：" + reports.toAbsolutePath(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("診斷工具已就緒，報告路徑：" + reports.toAbsolutePath() + "；自動追蹤登入連線已啟用（最多兩位、每次 120 秒）。");
        environment = "core=" + getServer().getVersion() + "\njava=" + System.getProperty("java.version") + "\nplugins=" +
            getServer().getPluginManager().getPlugins().values().stream().map(p -> p.getName() + ":" + p.getDescription().getVersion()).sorted().toList() +
            "\nPacket events are observations, not proof of delivery. No raw payloads captured.\n";
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers().values()) traceFor(player);
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            for (var entry : activeTraces().entrySet()) {
                Player p = entry.getKey();
                if (p != null && p.getLevel() != null) entry.getValue().add("CONTEXT world=" + Trace.clean(p.getLevel().getFolderName()) + " xyz=" + p.getFloorX() + "," + p.getFloorY() + "," + p.getFloorZ() + " tick=" + getServer().getTick() + " heapUsedMiB=" + ((Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576));
                if (System.nanoTime() >= entry.getValue().deadline) finish(entry.getKey(), "TIMEOUT");
            }
        }, 20);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!sender.hasPermission("tokaDiagnostics.admin")) return true;
        if (a.length == 0 || a[0].equalsIgnoreCase("status")) {
            sender.sendMessage("診斷 " + getDescription().getVersion() + "；追蹤中：" + traces.size() + "/2；待寫入：" + writer.getQueue().size());
            sender.sendMessage("報告路徑：" + reports.toAbsolutePath());
            sender.sendMessage("自動追蹤：開啟；每條連線一次、120 秒、同時最多兩位。diag start 可手動再次追蹤。");
            return true;
        }
        if (a.length < 2) return false;
        Player p = getServer().getPlayerExact(a[1]);
        if (p == null) { sender.sendMessage("玩家不在線。只有先啟動追蹤才會在斷線時自動匯出；請檢查 reports 或登入後先執行 diag start。"); return true; }
        Player id = p;

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start": {
                int seconds = 60;
                try { if (a.length > 2) seconds = Integer.parseInt(a[2]); } catch (NumberFormatException e) { sender.sendMessage("秒數必須是 10–300。"); return true; }
                if (seconds < 10 || seconds > 300 || traces.size() >= 2 || traces.containsKey(id)) { sender.sendMessage("限 10–300 秒，同時最多兩位，既有追蹤請先匯出。"); return true; }
                Trace trace = new Trace(2048, System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
                trace.add("START"); traces.put(id, trace); sender.sendMessage("追蹤已開始；斷線或到期自動匯出。"); break;
            }
            case "mark": {
                Trace trace = traces.get(id);
                if (trace != null) trace.add("MARK " + String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                sender.sendMessage(trace == null ? "尚未開始追蹤。" : "已加入標記（請勿輸入密碼）。"); break;
            }
            case "export":
                if (!traces.containsKey(id)) sender.sendMessage("這位玩家尚未開始追蹤，沒有資料可匯出。請先 diag start " + p.getName() + " 120。");
                else { finish(id, "MANUAL"); sender.sendMessage("已提交匯出；請查看後台的報告路徑。"); }
                break;
            default: return false;
        }
        return true;
    }
    private void packet(Player p, DataPacket packet, String direction, boolean cancelled) {
        // A connection can produce events before UUID assignment.
        if (p == null || packet == null) return;
        Trace trace = traceFor(p);
        if (trace == null) return;
        String detail = "";
        long knownBytes = 0;
        if (packet instanceof LoginPacket l) detail = " protocol=" + l.getProtocol();
        if (packet instanceof RequestNetworkSettingsPacket n) detail = " protocol=" + n.protocolVersion;
        if (packet instanceof UpdateBlockPacket b) detail = " xyz=" + b.x + "," + b.y + "," + b.z + " runtime=" + b.blockRuntimeId + " layer=" + b.dataLayer + " flags=" + b.flags;
        if (packet instanceof LevelChunkPacket c) {
            knownBytes = c.data == null ? 0 : c.data.length;
            detail = " chunk=" + c.chunkX + "," + c.chunkZ + " dimension=" + c.dimension + " sections=" + c.subChunkCount + " cache=" + c.cacheEnabled + " requestSubChunks=" + c.requestSubChunks + " blobCount=" + (c.blobIds==null?0:c.blobIds.length) + payloadSummary(c.data);
        }
        if (packet instanceof BlockEntityDataPacket b) {
            knownBytes = b.namedTag == null ? 0 : b.namedTag.length;
            detail = " xyz=" + b.x + "," + b.y + "," + b.z + payloadSummary(b.namedTag);
        }
        if (packet instanceof DisconnectPacket d) detail = " hideScreen=" + d.hideDisconnectionScreen;
        trace.packet(direction, packet.getClass().getSimpleName(), cancelled, knownBytes, detail);
        if(direction.equals("OUT")&&!cancelled) {
            if(packet instanceof LevelChunkPacket c)validate(trace,"LevelChunk",c.data,c.subChunkCount,c.dimension,"chunk="+c.chunkX+","+c.chunkZ);
            if(packet instanceof BlockEntityDataPacket b)validate(trace,"BlockEntityNBT",b.namedTag,-1,0,"xyz="+b.x+","+b.y+","+b.z);
            if(packet instanceof UpdateBlockPacket b){
                trace.beginValidation();
                String status=b.blockRuntimeId<0||b.dataLayer<0||b.dataLayer>1?"FAIL":"PASS";
                if(status.equals("PASS")&&cn.nukkit.level.GlobalBlockPalette.getLegacyFullId(b.blockRuntimeId)<0)status="UNMAPPED_SERVER_RUNTIME";
                trace.endValidation("UpdateBlockFields",status,"xyz="+b.x+","+b.y+","+b.z+" runtime="+b.blockRuntimeId+" layer="+b.dataLayer);
            }
        }
    }
    private void validate(Trace trace,String kind,byte[] bytes,int sections,int dimension,String where) {
        trace.beginValidation();
        if(bytes==null||bytes.length>262144){trace.endValidation(kind,"SKIPPED_SIZE",where+" bytes="+(bytes==null?-1:bytes.length));return;}
        if(validator.getQueue().remainingCapacity()==0){trace.endValidation(kind,"SKIPPED_QUEUE",where);return;}
        byte[] copy=bytes.clone();
        try {validator.execute(()->{
            try {
                PayloadValidator.Result result=sections<0?PayloadValidator.nbt(copy):PayloadValidator.chunk(copy,sections,dimension,id->cn.nukkit.level.GlobalBlockPalette.getLegacyFullId(id)>=0);
                trace.endValidation(kind,result.status(),where+" "+result.detail());
            }catch(RuntimeException e){trace.endValidation(kind,"VALIDATOR_ERROR",where+" "+e.getClass().getSimpleName());}
        });}catch(RejectedExecutionException e){trace.endValidation(kind,"SKIPPED_QUEUE",where);}
    }
    static String payloadSummary(byte[] data) {
        int length=data==null?0:data.length;
        CRC32 crc=new CRC32(); if(data!=null)crc.update(data,0,Math.min(length,4096));
        return " bytes="+length+" crc32First4096="+Long.toHexString(crc.getValue());
    }
    private static String position(cn.nukkit.level.Location location) {
        if(location==null)return "unknown";
        return (location.getLevel()==null?"unknown":Trace.clean(location.getLevel().getFolderName()))+":"+location.getFloorX()+","+location.getFloorY()+","+location.getFloorZ();
    }
    @EventHandler(priority = EventPriority.MONITOR) public void joined(PlayerJoinEvent e) {
        Trace trace=traceFor(e.getPlayer());if(trace!=null)trace.add("JOIN");
    }
    @EventHandler(priority = EventPriority.MONITOR) public void teleported(PlayerTeleportEvent e) {
        Trace trace=traceFor(e.getPlayer());if(trace!=null)trace.add("TELEPORT_EVENT cause="+e.getCause()+" cancelled="+e.isCancelled()+" from="+position(e.getFrom())+" to="+position(e.getTo()));
    }
    @EventHandler(priority = EventPriority.MONITOR) public void sent(DataPacketSendEvent e) { packet(e.getPlayer(), e.getPacket(), "OUT", e.isCancelled()); }
    @EventHandler(priority = EventPriority.MONITOR) public void received(DataPacketReceiveEvent e) { packet(e.getPlayer(), e.getPacket(), "IN", e.isCancelled()); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { finish(e.getPlayer(), "QUIT"); }
    private synchronized void finish(Player id, String reason) {
        if (id == null) return;
        Trace trace = traces.remove(id);
        if (trace == null) return;
        if (completed.size() >= 128) completed.remove(completed.keySet().iterator().next());
        completed.put(id, Boolean.TRUE);
        trace.add(reason);
        String name = "incident-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".zip";
        try { writer.execute(() -> {
            try {
                trace.awaitValidation();
                String snapshot=environment+trace.snapshot();
                Files.createDirectories(reports);
                // Hard disk bound: refuse additional reports rather than delete existing evidence.
                try (var files = Files.list(reports)) { if (files.filter(f -> f.toString().endsWith(".zip")).count() >= 50) throw new java.io.IOException("報告已達 50 份，請先移出舊報告"); }
                Path path = reports.resolve(name);
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
                    zip.putNextEntry(new ZipEntry("trace.txt")); zip.write((coreFingerprint()+snapshot).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("README.txt"));
                    zip.write(("Format 2: independent lifecycle, incoming, blocks, block-entities, chunks and other-outgoing buffers.\n"+
                        "dropped applies only to its channel; packet totals cover the whole trace. Per-second counts retain up to 301 seconds.\n"+
                        "knownPayloadBytes counts only chunk data and block-entity NBT, not encoded/wire bytes.\n"+
                        "crc32First4096 is a prefix fingerprint, not integrity validation or a full payload hash.\n"+
                        "TELEPORT_EVENT observes a request; subsequent CONTEXT confirms position.\n"+
                        "Packet events do not prove successful encoding, delivery or client acceptance.\n"+
                        "validation-totals: PASS is structural only; FAIL reports a structural violation.\n"+
                        "UNSUPPORTED, UNMAPPED_SERVER_RUNTIME, SKIPPED_* and VALIDATOR_ERROR are not PASS.\n"+
                        "Validation queue: 32 entries; payload cap: 256 KiB; export waits at most 2 seconds.\n"+
                        "validationPendingAtExport > 0 means results are incomplete. Raw payloads are not stored.\n"+
                        "No raw packets, login credentials, chat or block-entity NBT contents are saved.\n").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
                }
                getLogger().info("診斷報告：" + path);
            } catch (Exception e) { getLogger().error("診斷匯出失敗", e); }
        }); } catch (RejectedExecutionException e) { getLogger().warning("診斷匯出佇列已滿或正在關閉，未產生報告。"); }
    }
    // Only the single export worker invokes this; never hash a JAR on the packet/main thread.
    private String coreFingerprint() {
        if(coreFingerprint!=null)return coreFingerprint;
        try {
            Path core=Paths.get(getServer().getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if(!Files.isRegularFile(core))return coreFingerprint="coreJarOnDiskSha256=unavailable-directory\n";
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(core)) {byte[] block=new byte[65536];int count;while((count=input.read(block))!=-1)digest.update(block,0,count);}
            return coreFingerprint="coreJarOnDiskSha256="+HexFormat.of().formatHex(digest.digest())+"\n";
        }catch(Exception e){return coreFingerprint="coreJarOnDiskSha256=unavailable-"+e.getClass().getSimpleName()+"\n";}
    }
    @Override public void onDisable() {
        for (Player id : activeTraces().keySet()) finish(id, "DISABLE");
        validator.shutdown();
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) getLogger().warning("仍有診斷報告正在寫入。"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
