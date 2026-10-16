package dev.toka.diagnostics;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.*;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.*;
import cn.nukkit.network.protocol.*;
import cn.nukkit.plugin.PluginBase;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class Main extends PluginBase implements Listener {
    private final Map<UUID, Trace> traces = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), r -> { Thread t = new Thread(r, "tokaDiagnostics-export"); t.setDaemon(true); return t; });
    private Path reports;
    private String environment;
    @Override public void onEnable() {
        reports = getDataFolder().toPath().resolve("reports");
        environment = "core=" + getServer().getVersion() + "\njava=" + System.getProperty("java.version") + "\nplugins=" +
            getServer().getPluginManager().getPlugins().values().stream().map(p -> p.getName() + ":" + p.getDescription().getVersion()).sorted().toList() +
            "\nPacket events are observations, not proof of delivery. No raw payloads captured.\n";
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            for (var entry : traces.entrySet()) {
                Player p = getServer().getPlayer(entry.getKey()).orElse(null);
                if (p != null) entry.getValue().add("CONTEXT world=" + Trace.clean(p.getLevel().getFolderName()) + " xyz=" + p.getFloorX() + "," + p.getFloorY() + "," + p.getFloorZ());
                if (System.nanoTime() >= entry.getValue().deadline) finish(entry.getKey(), "TIMEOUT");
            }
        }, 20);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!sender.hasPermission("tokaDiagnostics.admin")) return true;
        if (a.length < 2) return false;
        Player p = getServer().getPlayerExact(a[1]);
        if (p == null) { sender.sendMessage("請指定線上的玩家；離線時已自動匯出。"); return true; }
        UUID id = p.getUniqueId();
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
            case "export": finish(id, "MANUAL"); sender.sendMessage("已提交匯出；請查看後台的報告路徑。"); break;
            default: return false;
        }
        return true;
    }
    private void packet(Player p, DataPacket packet, String direction, boolean cancelled) {
        Trace trace = traces.get(p.getUniqueId());
        if (trace == null) return;
        String detail = "";
        if (packet instanceof UpdateBlockPacket b) detail = " xyz=" + b.x + "," + b.y + "," + b.z + " runtime=" + b.blockRuntimeId + " layer=" + b.dataLayer + " flags=" + b.flags;
        if (packet instanceof LevelChunkPacket c) detail = " chunk=" + c.chunkX + "," + c.chunkZ + " dimension=" + c.dimension + " sections=" + c.subChunkCount + " bytes=" + (c.data == null ? 0 : c.data.length) + " cache=" + c.cacheEnabled;
        trace.add(direction + " " + packet.getClass().getSimpleName() + " cancelled=" + cancelled + detail);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void sent(DataPacketSendEvent e) { packet(e.getPlayer(), e.getPacket(), "OUT", e.isCancelled()); }
    @EventHandler(priority = EventPriority.MONITOR) public void received(DataPacketReceiveEvent e) { packet(e.getPlayer(), e.getPacket(), "IN", e.isCancelled()); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { finish(e.getPlayer().getUniqueId(), "QUIT"); }
    private void finish(UUID id, String reason) {
        Trace trace = traces.remove(id);
        if (trace == null) return;
        trace.add(reason);
        String snapshot = environment + trace.snapshot();
        String name = "incident-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".zip";
        try { writer.execute(() -> {
            try {
                Files.createDirectories(reports);
                // Hard disk bound: refuse additional reports rather than delete existing evidence.
                try (var files = Files.list(reports)) { if (files.filter(f -> f.toString().endsWith(".zip")).count() >= 50) throw new java.io.IOException("報告已達 50 份，請先移出舊報告"); }
                Path path = reports.resolve(name);
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
                    zip.putNextEntry(new ZipEntry("trace.txt")); zip.write(snapshot.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                }
                getLogger().info("診斷報告：" + path);
            } catch (Exception e) { getLogger().error("診斷匯出失敗", e); }
        }); } catch (RejectedExecutionException e) { getLogger().warning("診斷匯出佇列已滿或正在關閉，未產生報告。"); }
    }
    @Override public void onDisable() {
        for (UUID id : List.copyOf(traces.keySet())) finish(id, "DISABLE");
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) getLogger().warning("仍有診斷報告正在寫入。"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
