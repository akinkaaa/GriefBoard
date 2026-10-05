package ru.griefboard;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GriefBoard extends JavaPlugin implements Listener {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final List<String> FIELDS = Arrays.asList("balance", "pillikov", "romashki", "elo", "clan");

    private File dataFile;
    private FileConfiguration data;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private int taskId = -1;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, Long> rtpCooldown = new HashMap<>();
    private final Random random = new Random();
    private static final Set<Material> UNSAFE = new HashSet<>(Arrays.asList(
            Material.LAVA, Material.WATER, Material.MAGMA_BLOCK, Material.CACTUS, Material.FIRE,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE));

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyRank(p);
            createBoard(p);
        }
        startTask();
    }

    @Override
    public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        saveData();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            p.setPlayerListName(null);
            p.setPlayerListHeaderFooter("", "");
            PermissionAttachment att = attachments.remove(p.getUniqueId());
            if (att != null) {
                try { p.removeAttachment(att); } catch (IllegalArgumentException ignored) { }
            }
        }
        boards.clear();
    }

    private void startTask() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        long interval = Math.max(1, getConfig().getLong("update-ticks", 20));
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) updateBoard(p);
        }, 20L, interval);
    }

    // ---------- events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyRank(e.getPlayer());
        createBoard(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        attachments.remove(e.getPlayer().getUniqueId());
    }

    // ---------- scoreboard ----------

    private void createBoard(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("griefboard", "dummy", color(getConfig().getString("title", "&6ГРИФ #1")));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        int size = getConfig().getStringList("lines").size();
        for (int i = 0; i < size; i++) {
            // уникальная невидимая запись для каждой строки
            String entry = ChatColor.values()[i % 16].toString() + ChatColor.RESET + (i >= 16 ? ChatColor.values()[i - 16] : "");
            Team team = sb.registerNewTeam("l" + i);
            team.addEntry(entry);
            obj.getScore(entry).setScore(size - i);
        }
        boards.put(p.getUniqueId(), sb);
        p.setScoreboard(sb);
        updateBoard(p);
    }

    private void updateBoard(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) return;
        List<String> lines = getConfig().getStringList("lines");

        Objective obj = sb.getObjective("griefboard");
        if (obj != null) obj.setDisplayName(color(getConfig().getString("title", "&6ГРИФ #1")));

        for (int i = 0; i < lines.size(); i++) {
            Team team = sb.getTeam("l" + i);
            if (team == null) continue;
            String text = color(replace(lines.get(i), p));
            if (text.length() > 64) text = text.substring(0, 64);
            team.setPrefix(text);
        }
        updateTab(p);
    }

    private void updateTab(Player p) {
        if (!getConfig().getBoolean("tab.enabled", true)) return;
        String header = String.join("\n", getConfig().getStringList("tab.header"));
        String footer = String.join("\n", getConfig().getStringList("tab.footer"));
        p.setPlayerListHeaderFooter(color(replace(header, p)), color(replace(footer, p)));
        String fmt = getConfig().getString("tab.name-format", "");
        if (!fmt.isEmpty()) p.setPlayerListName(color(replace(fmt, p)));
    }

    private String replace(String s, Player p) {
        String base = "players." + p.getUniqueId() + ".";
        String clan = data.getString(base + "clan", "");
        if (clan.isEmpty()) clan = getConfig().getString("no-clan", "Без клана");

        return s.replace("{player}", p.getName())
                .replace("{rank}", getRank(p))
                .replace("{clan}", clan)
                .replace("{kills}", String.valueOf(p.getStatistic(Statistic.PLAYER_KILLS)))
                .replace("{deaths}", String.valueOf(p.getStatistic(Statistic.DEATHS)))
                .replace("{elo}", fmt(data.getInt(base + "elo", getConfig().getInt("start-elo", 1000))))
                .replace("{balance}", fmt(data.getInt(base + "balance", 0)))
                .replace("{pillikov}", fmt(data.getInt(base + "pillikov", 0)))
                .replace("{romashki}", fmt(data.getInt(base + "romashki", 0)))
                .replace("{ping}", String.valueOf(getPing(p)))
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("{tps}", String.format(Locale.US, "%.1f", getTps()));
    }

    // ---------- свои ранги (без LuckPerms) ----------

    /** Ключ ранга игрока: сохранённый командой /rank set или ранг по умолчанию. */
    private String rankKey(UUID id) {
        ConfigurationSection ranks = getConfig().getConfigurationSection("ranks");
        String stored = data.getString("ranks." + id);
        if (stored != null && ranks != null) {
            for (String key : ranks.getKeys(false)) {
                if (key.equalsIgnoreCase(stored)) return key;
            }
        }
        return getConfig().getString("default-rank", "player");
    }

    /** Красивое название ранга для скорборда, таба и чата. */
    private String getRank(Player p) {
        ConfigurationSection ranks = getConfig().getConfigurationSection("ranks");
        String key = rankKey(p.getUniqueId());
        if (ranks != null && ranks.isConfigurationSection(key)) {
            return ranks.getString(key + ".display", key);
        }
        return key; // если default-rank записан обычным текстом
    }

    /** Выдаёт игроку права ранга (список permissions и флаг op) из config.yml. */
    private void applyRank(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try { p.removeAttachment(old); } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection ranks = getConfig().getConfigurationSection("ranks");
        String key = rankKey(p.getUniqueId());
        boolean wantsOp = false;
        if (ranks != null && ranks.isConfigurationSection(key)) {
            wantsOp = ranks.getBoolean(key + ".op", false);
            PermissionAttachment att = p.addAttachment(this);
            for (String perm : ranks.getStringList(key + ".permissions")) {
                if (perm.startsWith("-")) att.setPermission(perm.substring(1), false);
                else att.setPermission(perm, true);
            }
            attachments.put(p.getUniqueId(), att);
        }
        // OP выдаётся рангом только если ранг так настроен; выданный рангом OP
        // снимается при смене на ранг без op. Вручную выданный OP не трогаем.
        String opPath = "granted-op." + p.getUniqueId();
        if (wantsOp) {
            if (!p.isOp()) {
                p.setOp(true);
                data.set(opPath, true);
                saveData();
            }
        } else if (data.getBoolean(opPath, false)) {
            p.setOp(false);
            data.set(opPath, null);
            saveData();
        }
    }

    private boolean handleRank(CommandSender sender, String[] args, String label) {
        ConfigurationSection ranks = getConfig().getConfigurationSection("ranks");
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("§eРанги: §f" + (ranks == null ? "-" : String.join(", ", ranks.getKeys(false))));
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("reset"))) {
            Player online = Bukkit.getPlayerExact(args[1]);
            UUID id = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
            if (args[0].equalsIgnoreCase("info")) {
                sender.sendMessage("§eРанг " + args[1] + ": §f" + rankKey(id));
            } else {
                data.set("ranks." + id, null);
                saveData();
                if (online != null) {
                    applyRank(online);
                    updateBoard(online);
                }
                sender.sendMessage("§aРанг " + args[1] + " сброшен до ранга по умолчанию.");
            }
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            String key = null;
            if (ranks != null) {
                for (String k : ranks.getKeys(false)) {
                    if (k.equalsIgnoreCase(args[2])) key = k;
                }
            }
            if (key == null) {
                sender.sendMessage("§cТакого ранга нет. Список: /" + label + " list");
                return true;
            }
            Player online = Bukkit.getPlayerExact(args[1]);
            UUID id = online != null ? online.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
            data.set("ranks." + id, key);
            saveData();
            if (online != null) {
                applyRank(online);
                updateBoard(online);
            }
            sender.sendMessage("§aРанг " + args[1] + " теперь: §f" + key);
            return true;
        }
        sender.sendMessage("§e/" + label + " set <ник> <ранг>");
        sender.sendMessage("§e/" + label + " reset <ник>");
        sender.sendMessage("§e/" + label + " info <ник>");
        sender.sendMessage("§e/" + label + " list");
        return true;
    }

    private static String fmt(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    // ---------- ping (1.16.5, через reflection) ----------

    private Method getHandleMethod;
    private Field pingField;

    private int getPing(Player p) {
        try {
            if (getHandleMethod == null) {
                getHandleMethod = p.getClass().getMethod("getHandle");
            }
            Object handle = getHandleMethod.invoke(p);
            if (pingField == null) {
                pingField = handle.getClass().getField("ping");
            }
            return pingField.getInt(handle);
        } catch (Exception ex) {
            return 0;
        }
    }

    // ---------- TPS (1.16.5, через reflection) ----------

    private Object serverHandle;
    private Field tpsField;

    private double getTps() {
        try {
            if (tpsField == null) {
                Object server = Bukkit.getServer();
                serverHandle = server.getClass().getMethod("getServer").invoke(server);
                tpsField = serverHandle.getClass().getField("recentTps");
            }
            double[] t = (double[]) tpsField.get(serverHandle);
            return Math.min(20.0, t[0]);
        } catch (Exception ex) {
            return 20.0;
        }
    }

    // ---------- цвета (&-коды и &#RRGGBB) ----------

    private static String color(String s) {
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, ChatColor.of("#" + m.group(1)).toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    // ---------- данные ----------

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    // ---------- команды ----------

    // ---------- чат с рангами ----------

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (!getConfig().getBoolean("chat.enabled", true)) return;
        Player p = e.getPlayer();
        String f = getConfig().getString("chat.format", "{rank} &f{player}&8: &7{message}");
        f = f.replace("{rank}", getRank(p)).replace("{player}", p.getName());
        f = color(f.replace("%", "%%"));
        f = f.replace("{message}", "%2$s");
        e.setFormat(f);
    }

    // ---------- RTP и яма ----------

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null || to.getWorld() == null) return;
        if (!to.getWorld().getName().equals(getConfig().getString("rtp.lobby-world", "lobby"))) return;
        if (to.getY() >= getConfig().getDouble("pit.trigger-below", 58)) return;

        Player p = e.getPlayer();
        if (isInPit(to)) {
            randomTeleport(p);
        } else if (to.getY() < 40) {
            // упал мимо ямы (не должно случаться из-за границы) — вернуть на спавн
            p.teleport(to.getWorld().getSpawnLocation());
        }
    }

    private boolean isInPit(Location l) {
        if (!data.contains("pit.x")) return false;
        double dx = l.getX() - data.getDouble("pit.x");
        double dz = l.getZ() - data.getDouble("pit.z");
        double r = getConfig().getDouble("pit.radius", 5.0);
        return dx * dx + dz * dz <= r * r;
    }

    private void randomTeleport(Player p) {
        final UUID id = p.getUniqueId();
        if (!busy.add(id)) return;
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                World w = Bukkit.getWorld(getConfig().getString("rtp.world", "world"));
                if (w == null) {
                    p.sendMessage("§cМир для телепортации не найден.");
                    return;
                }
                Location loc = findSafe(w);
                if (loc == null) {
                    p.sendMessage("§cНе удалось найти безопасное место, попробуй ещё раз.");
                    return;
                }
                p.setFallDistance(0);
                p.teleport(loc);
            } finally {
                busy.remove(id);
            }
        });
    }

    private Location findSafe(World w) {
        int min = getConfig().getInt("rtp.min-radius", 300);
        int max = getConfig().getInt("rtp.max-radius", 10000);
        int tries = getConfig().getInt("rtp.tries", 30);
        double half = w.getWorldBorder().getSize() / 2.0 - 16;
        if (max > half) max = (int) Math.max(half, 1);
        if (min >= max) min = 0;
        Location c = w.getWorldBorder().getCenter();

        for (int i = 0; i < tries; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(c.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(c.getZ() + Math.sin(angle) * dist);

            Block ground = w.getHighestBlockAt(x, z);
            Material m = ground.getType();
            if (!m.isSolid() || ground.isLiquid() || UNSAFE.contains(m) || m.name().contains("LEAVES")) continue;
            if (ground.getRelative(0, 1, 0).getType() != Material.AIR) continue;
            if (ground.getRelative(0, 2, 0).getType() != Material.AIR) continue;
            return new Location(w, x + 0.5, ground.getY() + 1, z + 0.5);
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("rank")) {
            return handleRank(sender, args, label);
        }
        if (cmd.getName().equalsIgnoreCase("rtp")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Только для игроков.");
                return true;
            }
            Player p = (Player) sender;
            boolean admin = p.hasPermission("griefboard.admin");
            String lobby = getConfig().getString("rtp.lobby-world", "lobby");
            if (getConfig().getBoolean("rtp.only-from-lobby", true) && !admin
                    && !p.getWorld().getName().equals(lobby)) {
                p.sendMessage("§c/rtp работает только на спавне.");
                return true;
            }
            long now = System.currentTimeMillis();
            long wait = getConfig().getLong("rtp.cooldown-seconds", 5) * 1000L;
            Long last = rtpCooldown.get(p.getUniqueId());
            if (!admin && last != null && now - last < wait) {
                p.sendMessage("§cПодожди ещё " + ((wait - (now - last)) / 1000 + 1) + " сек.");
                return true;
            }
            rtpCooldown.put(p.getUniqueId(), now);
            randomTeleport(p);
            return true;
        }
        if (!sender.hasPermission("griefboard.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            data = YamlConfiguration.loadConfiguration(dataFile);
            for (Player p : Bukkit.getOnlinePlayers()) {
                applyRank(p);
                createBoard(p);
            }
            startTask();
            sender.sendMessage("§aGriefBoard перезагружен.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("setpit")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Только для игроков.");
                return true;
            }
            Location l = ((Player) sender).getLocation();
            data.set("pit.world", l.getWorld().getName());
            data.set("pit.x", l.getX());
            data.set("pit.y", l.getY());
            data.set("pit.z", l.getZ());
            saveData();
            sender.sendMessage("§aЦентр ямы установлен здесь.");
            return true;
        }
        if (args.length == 4 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add"))) {
            Player target = Bukkit.getPlayerExact(args[1]);
            String field = args[2].toLowerCase();
            if (target == null) {
                sender.sendMessage("§cИгрок не найден.");
                return true;
            }
            if (!FIELDS.contains(field)) {
                sender.sendMessage("§cПоле: " + FIELDS);
                return true;
            }
            String path = "players." + target.getUniqueId() + "." + field;
            if (field.equals("clan")) {
                data.set(path, args[3].equals("-") ? "" : args[3]);
            } else {
                int val;
                try {
                    val = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cЧисло неверное.");
                    return true;
                }
                int cur = data.getInt(path, field.equals("elo") ? getConfig().getInt("start-elo", 1000) : 0);
                data.set(path, args[0].equalsIgnoreCase("add") ? cur + val : val);
            }
            saveData();
            updateBoard(target);
            sender.sendMessage("§aГотово.");
            return true;
        }
        sender.sendMessage("§e/" + label + " reload");
        sender.sendMessage("§e/" + label + " setpit §7- центр ямы там, где ты стоишь");
        sender.sendMessage("§e/" + label + " set|add <ник> <balance|pillikov|romashki|elo|clan> <значение>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("rtp")) return out;
        if (cmd.getName().equalsIgnoreCase("rank")) {
            if (args.length == 1) out.addAll(Arrays.asList("set", "reset", "info", "list"));
            else if (args.length == 2 && !args[0].equalsIgnoreCase("list")) {
                for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
            } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                ConfigurationSection r = getConfig().getConfigurationSection("ranks");
                if (r != null) out.addAll(r.getKeys(false));
            }
            return out;
        }
        if (args.length == 1) out.addAll(Arrays.asList("reload", "set", "add", "setpit"));
        else if (args.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (args.length == 3) out.addAll(FIELDS);
        return out;
    }
}
