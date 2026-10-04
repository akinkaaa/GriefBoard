package ru.griefboard;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) createBoard(p);
        startTask();
    }

    @Override
    public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        saveData();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
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
        createBoard(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
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
                .replace("{ping}", String.valueOf(getPing(p)));
    }

    private String getRank(Player p) {
        ConfigurationSection ranks = getConfig().getConfigurationSection("ranks");
        if (ranks != null) {
            for (String key : ranks.getKeys(false)) {
                String perm = ranks.getString(key + ".permission", "");
                if (!perm.isEmpty() && p.hasPermission(perm)) {
                    return ranks.getString(key + ".display", key);
                }
            }
        }
        return getConfig().getString("default-rank", "&7Игрок");
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

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("griefboard.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            data = YamlConfiguration.loadConfiguration(dataFile);
            for (Player p : Bukkit.getOnlinePlayers()) createBoard(p);
            startTask();
            sender.sendMessage("§aGriefBoard перезагружен.");
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
        sender.sendMessage("§e/" + label + " set|add <ник> <balance|pillikov|romashki|elo|clan> <значение>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(Arrays.asList("reload", "set", "add"));
        else if (args.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (args.length == 3) out.addAll(FIELDS);
        return out;
    }
}
