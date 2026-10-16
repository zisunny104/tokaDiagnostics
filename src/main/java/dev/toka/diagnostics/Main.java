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
    private Map<Player, Trace> activeTraces() { synchronized (traces) { return new IdentityHashMap<>(traces); } }
    private Path reports;
    private String environment;
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
                if (p != null && p.getLevel() != null) entry.getValue().add("CONTEXT world=" + Trace.clean(p.getLevel().getFolderName()) + " xyz=" + p.getFloorX() + "," + p.getFloorY() + "," + p.getFloorZ());
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
        if (packet instanceof UpdateBlockPacket b) detail = " xyz=" + b.x + "," + b.y + "," + b.z + " runtime=" + b.blockRuntimeId + " layer=" + b.dataLayer + " flags=" + b.flags;
        if (packet instanceof LevelChunkPacket c) detail = " chunk=" + c.chunkX + "," + c.chunkZ + " dimension=" + c.dimension + " sections=" + c.subChunkCount + " bytes=" + (c.data == null ? 0 : c.data.length) + " cache=" + c.cacheEnabled;
        trace.add(direction + " " + packet.getClass().getSimpleName() + " cancelled=" + cancelled + detail);
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
        for (Player id : activeTraces().keySet()) finish(id, "DISABLE");
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) getLogger().warning("仍有診斷報告正在寫入。"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
