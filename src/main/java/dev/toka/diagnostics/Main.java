package dev.toka.diagnostics;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
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
import dev.toka.i18n.Messages;

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
    // Created in onEnable: a shut-down executor cannot be reused, so a re-enable needs fresh ones.
    private ThreadPoolExecutor writer;
    private ThreadPoolExecutor validator;
    private Messages messages;
    private String text(String key, Object... args) { return messages.text(key, args); }
    private Map<Player, Trace> activeTraces() { synchronized (traces) { return new IdentityHashMap<>(traces); } }
    private Path reports;
    private String environment;
    private String coreFingerprint;
    @Override public void onEnable() {
        messages = new Messages(getClass().getClassLoader(), getDataFolder().toPath(), "zh-TW", line -> getLogger().warning(line));
        traces.clear();
        completed.clear();
        coreFingerprint = null;
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), r -> { Thread t = new Thread(r, "tokaDiagnostics-export"); t.setDaemon(true); return t; });
        validator = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> { Thread t=new Thread(r,"tokaDiagnostics-validate");t.setDaemon(true);return t; });
        suggest("diag",
            new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"status", "help"})},
            new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"start"}), CommandParameter.newType("玩家", CommandParamType.TARGET), CommandParameter.newType("秒數", true, CommandParamType.INT)},
            new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"mark"}), CommandParameter.newType("玩家", CommandParamType.TARGET), CommandParameter.newType("標記", true, CommandParamType.RAWTEXT)},
            new CommandParameter[]{CommandParameter.newEnum("動作", new String[]{"export"}), CommandParameter.newType("玩家", CommandParamType.TARGET)});
        reports = getDataFolder().toPath().resolve("reports");
        try { Files.createDirectories(reports); }
        catch (java.io.IOException e) {
            getLogger().error(text("log.folder", reports.toAbsolutePath()), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info(text("log.ready", reports.toAbsolutePath()));
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
    private void suggest(String name, CommandParameter[]... forms) {
        Command registered = (Command) getCommand(name);
        if (registered == null) return;
        registered.getCommandParameters().clear();
        for (int i = 0; i < forms.length; i++) registered.addCommandParameters("choice" + i, forms[i]);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!sender.hasPermission("tokaDiagnostics.admin")) return true;
        if (a.length > 0 && a[0].equalsIgnoreCase("help")) {
            for (String line : messages.lines("help")) sender.sendMessage(line);
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("status")) {
            sender.sendMessage(text("status.line", getDescription().getVersion(), traces.size(), writer.getQueue().size()));
            sender.sendMessage(text("status.path", reports.toAbsolutePath()));
            sender.sendMessage(text("status.auto"));
            return true;
        }
        if (a.length < 2) return false;
        Player p = getServer().getPlayerExact(a[1]);
        if (p == null) { sender.sendMessage(text("cmd.offline")); return true; }
        Player id = p;

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "start": {
                int seconds = 60;
                try { if (a.length > 2) seconds = Integer.parseInt(a[2]); } catch (NumberFormatException e) { sender.sendMessage(text("cmd.seconds")); return true; }
                if (seconds < 10 || seconds > 300 || traces.size() >= 2 || traces.containsKey(id)) { sender.sendMessage(text("cmd.limit")); return true; }
                Trace trace = new Trace(2048, System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
                trace.add("START"); traces.put(id, trace); sender.sendMessage(text("cmd.started")); break;
            }
            case "mark": {
                Trace trace = traces.get(id);
                if (trace != null) trace.add("MARK " + String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                sender.sendMessage(text(trace == null ? "cmd.no_trace" : "cmd.marked")); break;
            }
            case "export":
                if (!traces.containsKey(id)) sender.sendMessage(text("cmd.no_data", p.getName()));
                else { finish(id, "MANUAL"); sender.sendMessage(text("cmd.exported")); }
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
                try (var files = Files.list(reports)) { if (files.filter(f -> f.toString().endsWith(".zip")).count() >= 50) throw new java.io.IOException(text("log.full")); }
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
                getLogger().info(text("log.report", path));
            } catch (Exception e) { getLogger().error(text("log.export_failed", e.getClass().getSimpleName()), e); }
        }); } catch (RejectedExecutionException e) { getLogger().warning(text("log.queue_full")); }
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
        if (writer == null) return;
        for (Player id : activeTraces().keySet()) finish(id, "DISABLE");
        validator.shutdown();
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) getLogger().warning(text("log.still_writing")); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
