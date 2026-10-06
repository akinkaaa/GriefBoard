package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * Свой вход и регистрация (вместо AuthMe).
 * Пароли хранятся только в виде хеша PBKDF2-SHA256 с солью в plugins/GriefBoard/auth.yml.
 */
public class AuthManager implements Listener, CommandExecutor {

    private static final int ITER = 65536;
    private static final Set<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList(
            "/login", "/l", "/log", "/register", "/reg"));
    private static final Map<String, String> DEF = new HashMap<>();

    static {
        DEF.put("register-request", "%nl%&8&m                                  %nl%&6&l  ГРИФ &8| &fРегистрация%nl% %nl%&7  Привет, &f%player%&7!%nl%&7  Придумай пароль и введи:%nl%&e  /register &f<пароль> <повтор пароля>%nl%&8&m                                  %nl%");
        DEF.put("login-request", "%nl%&8&m                                  %nl%&6&l  ГРИФ &8| &fВход%nl% %nl%&7  С возвращением, &f%player%&7!%nl%&7  Введи свой пароль:%nl%&e  /login &f<пароль>%nl%&8&m                                  %nl%");
        DEF.put("register-title", "&6&lРегистрация");
        DEF.put("register-subtitle", "&e/register &f<пароль> <повтор>");
        DEF.put("login-title", "&6&lВход");
        DEF.put("login-subtitle", "&e/login &f<пароль>");
        DEF.put("register-usage", "&8[&6ГРИФ&8] &cИспользуй: &e/register &f<пароль> <повтор пароля>");
        DEF.put("login-usage", "&8[&6ГРИФ&8] &cИспользуй: &e/login &f<пароль>");
        DEF.put("change-usage", "&8[&6ГРИФ&8] &cИспользуй: &e/changepassword &f<старый> <новый>");
        DEF.put("password-mismatch", "&8[&6ГРИФ&8] &cПароли не совпадают, попробуй ещё раз.");
        DEF.put("password-short", "&8[&6ГРИФ&8] &cПароль должен быть от %min% до 64 символов.");
        DEF.put("password-name", "&8[&6ГРИФ&8] &cНельзя использовать ник в качестве пароля.");
        DEF.put("name-taken", "&8[&6ГРИФ&8] &cЭтот ник уже зарегистрирован. Войди: &e/login &f<пароль>");
        DEF.put("not-registered", "&8[&6ГРИФ&8] &cТы ещё не зарегистрирован: &e/register &f<пароль> <повтор>");
        DEF.put("registered", "&8[&6ГРИФ&8] &aТы успешно зарегистрирован! &7Приятной игры, &f%player%&7.");
        DEF.put("login-success", "&8[&6ГРИФ&8] &aТы успешно вошёл. &7С возвращением, &f%player%&7!");
        DEF.put("wrong-password", "&8[&6ГРИФ&8] &cНеверный пароль!");
        DEF.put("too-many-attempts", "&cСлишком много неверных паролей.");
        DEF.put("timeout", "&cВремя на вход вышло. Зайди на сервер ещё раз.");
        DEF.put("already-logged", "&8[&6ГРИФ&8] &cТы уже вошёл в аккаунт.");
        DEF.put("must-auth-command", "&8[&6ГРИФ&8] &cСначала войди: &e/login &cили зарегистрируйся: &e/register");
        DEF.put("must-auth-chat", "&8[&6ГРИФ&8] &cЧат доступен только после входа.");
        DEF.put("name-case", "&cЭтот ник зарегистрирован с другими заглавными буквами. Зайди с точным ником.");
        DEF.put("password-changed", "&8[&6ГРИФ&8] &aПароль успешно изменён.");
        DEF.put("wrong-old-password", "&8[&6ГРИФ&8] &cСтарый пароль неверный.");
        DEF.put("session", "&8[&6ГРИФ&8] &aТы вошёл автоматически. &7С возвращением, &f%player%&7!");
    }

    private final GriefBoard plugin;
    private File file;
    private YamlConfiguration acc;
    private final Set<UUID> authed = new HashSet<>();
    private final Map<UUID, Integer> waited = new HashMap<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private int taskId = -1;

    AuthManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    // ---------- запуск / остановка ----------

    void enable() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "auth.yml");
        acc = YamlConfiguration.loadConfiguration(file);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (String name : new String[]{"login", "register", "changepassword"}) {
            if (plugin.getCommand(name) != null) plugin.getCommand(name).setExecutor(this);
        }
        for (Player p : Bukkit.getOnlinePlayers()) handleJoin(p);

        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            int timeout = cfgInt("timeout-seconds", 60);
            int remind = Math.max(1, cfgInt("remind-seconds", 5));
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                UUID id = p.getUniqueId();
                if (authed.contains(id)) continue;
                int w = waited.merge(id, 1, Integer::sum);
                if (w >= timeout) {
                    p.kickPlayer(c(msg("timeout", p)));
                    continue;
                }
                if (w % remind == 0) remind(p);
            }
        }, 20L, 20L);
    }

    void disable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        for (Player p : Bukkit.getOnlinePlayers()) touch(p);
        save();
    }

    // ---------- вход игрока ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        handleJoin(e.getPlayer());
    }

    private void handleJoin(Player p) {
        String base = base(p);
        if (acc.contains(base)) {
            String stored = acc.getString(base + ".name", p.getName());
            if (!stored.equals(p.getName())) {
                Bukkit.getScheduler().runTask(plugin, () -> p.kickPlayer(c(msg("name-case", p))));
                return;
            }
            long session = cfgLong("session-minutes", 10) * 60_000L;
            if (session > 0 && ip(p).equals(acc.getString(base + ".ip", ""))
                    && System.currentTimeMillis() - acc.getLong(base + ".last-seen", 0) < session) {
                authed.add(p.getUniqueId());
                send(p, "session");
                return;
            }
        }
        authed.remove(p.getUniqueId());
        waited.put(p.getUniqueId(), 0);
        attempts.remove(p.getUniqueId());
        remind(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (authed.contains(p.getUniqueId())) {
            touch(p);
            save();
        }
        authed.remove(p.getUniqueId());
        waited.remove(p.getUniqueId());
        attempts.remove(p.getUniqueId());
    }

    /** Запоминает IP и время последнего пребывания (для сессии). */
    private void touch(Player p) {
        if (!authed.contains(p.getUniqueId())) return;
        String base = base(p);
        if (!acc.contains(base)) return;
        acc.set(base + ".ip", ip(p));
        acc.set(base + ".last-seen", System.currentTimeMillis());
    }

    private void remind(Player p) {
        boolean registered = acc.contains(base(p));
        String key = registered ? "login" : "register";
        send(p, key + "-request");
        p.sendTitle(c(msg(key + "-title", p)), c(msg(key + "-subtitle", p)), 0, 110, 10);
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        Player p = (Player) sender;
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "register": register(p, args); break;
            case "login": login(p, args); break;
            case "changepassword": change(p, args); break;
            default: break;
        }
        return true;
    }

    private void register(Player p, String[] args) {
        UUID id = p.getUniqueId();
        String base = base(p);
        if (authed.contains(id)) { send(p, "already-logged"); return; }
        if (acc.contains(base)) { send(p, "name-taken"); return; }
        if (args.length != 2) { send(p, "register-usage"); return; }
        if (!args[0].equals(args[1])) { send(p, "password-mismatch"); return; }
        String err = validate(args[0], p.getName());
        if (err != null) { send(p, err); return; }

        final String pw = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String stored = hash(pw);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline() || acc.contains(base)) return;
                acc.set(base + ".name", p.getName());
                acc.set(base + ".hash", stored);
                acc.set(base + ".ip", ip(p));
                acc.set(base + ".last-seen", System.currentTimeMillis());
                save();
                success(p);
                send(p, "registered");
            });
        });
    }

    private void login(Player p, String[] args) {
        UUID id = p.getUniqueId();
        String base = base(p);
        if (authed.contains(id)) { send(p, "already-logged"); return; }
        final String stored = acc.getString(base + ".hash");
        if (stored == null) { send(p, "not-registered"); return; }
        if (args.length != 1) { send(p, "login-usage"); return; }

        final String pw = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final boolean ok = verify(pw, stored);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (ok) {
                    acc.set(base + ".ip", ip(p));
                    acc.set(base + ".last-seen", System.currentTimeMillis());
                    save();
                    success(p);
                    send(p, "login-success");
                } else {
                    int a = attempts.merge(id, 1, Integer::sum);
                    if (a >= cfgInt("max-attempts", 5)) {
                        p.kickPlayer(c(msg("too-many-attempts", p)));
                    } else {
                        send(p, "wrong-password");
                    }
                }
            });
        });
    }

    private void change(Player p, String[] args) {
        UUID id = p.getUniqueId();
        String base = base(p);
        if (!authed.contains(id)) { send(p, "must-auth-command"); return; }
        if (args.length != 2) { send(p, "change-usage"); return; }
        String err = validate(args[1], p.getName());
        if (err != null) { send(p, err); return; }
        final String stored = acc.getString(base + ".hash");
        if (stored == null) { send(p, "not-registered"); return; }

        final String oldPw = args[0];
        final String newPw = args[1];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final boolean ok = verify(oldPw, stored);
            final String fresh = ok ? hash(newPw) : null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (!ok) {
                    send(p, "wrong-old-password");
                    return;
                }
                acc.set(base + ".hash", fresh);
                save();
                send(p, "password-changed");
            });
        });
    }

    /** Админ: удалить аккаунт игрока (после этого он регистрируется заново). */
    boolean unregister(String name) {
        String base = "accounts." + name.toLowerCase(Locale.ROOT);
        if (!acc.contains(base)) return false;
        acc.set(base, null);
        save();
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            authed.remove(p.getUniqueId());
            waited.put(p.getUniqueId(), 0);
            remind(p);
        }
        return true;
    }

    private void success(Player p) {
        authed.add(p.getUniqueId());
        waited.remove(p.getUniqueId());
        attempts.remove(p.getUniqueId());
        p.resetTitle();
    }

    private String validate(String pw, String name) {
        int min = cfgInt("min-password-length", 6);
        if (pw.length() < min || pw.length() > 64) return "password-short";
        if (pw.equalsIgnoreCase(name)) return "password-name";
        return null;
    }

    // ---------- ограничения для тех, кто не вошёл ----------

    private boolean locked(Player p) {
        return !authed.contains(p.getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!locked(p)) return;
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getZ() != t.getZ() || t.getY() > f.getY()) {
            Location back = f.clone();
            back.setYaw(t.getYaw());
            back.setPitch(t.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (locked(e.getPlayer())) {
            e.setCancelled(true);
            send(e.getPlayer(), "must-auth-chat");
        }
    }

    @EventHandler
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!locked(p)) return;
        String first = e.getMessage().split(" ")[0].toLowerCase(Locale.ROOT);
        if (ALLOWED_COMMANDS.contains(first)) return;
        e.setCancelled(true);
        send(p, "must-auth-command");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player && locked((Player) e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && locked((Player) e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && locked((Player) e.getDamager())) e.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && locked((Player) e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player && locked((Player) e.getWhoClicked())) e.setCancelled(true);
    }

    @EventHandler
    public void onInvOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player && locked((Player) e.getPlayer())) e.setCancelled(true);
    }

    // ---------- пароли (PBKDF2 + соль) ----------

    private static byte[] pbkdf2(String pw, byte[] salt, int iter, int bits) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, iter, bits);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private static String hash(String pw) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] h = pbkdf2(pw, salt, ITER, 256);
            Base64.Encoder enc = Base64.getEncoder();
            return "pbkdf2$" + ITER + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(h);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean verify(String pw, String stored) {
        try {
            String[] parts = stored.split("\\$");
            int iter = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(pw, salt, iter, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ex) {
            return false;
        }
    }

    // ---------- вспомогательное ----------

    private String base(Player p) {
        return "accounts." + p.getName().toLowerCase(Locale.ROOT);
    }

    private String ip(Player p) {
        return p.getAddress() != null && p.getAddress().getAddress() != null
                ? p.getAddress().getAddress().getHostAddress() : "";
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt("auth." + key, def);
    }

    private long cfgLong(String key, long def) {
        return plugin.getConfig().getLong("auth." + key, def);
    }

    private String msg(String key, Player p) {
        String raw = plugin.getConfig().getString("auth.messages." + key, DEF.getOrDefault(key, key));
        return raw.replace("%player%", p.getName()).replace("%min%", String.valueOf(cfgInt("min-password-length", 6)));
    }

    private void send(Player p, String key) {
        String text = c(msg(key, p));
        for (String line : text.split("%nl%")) p.sendMessage(line);
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private void save() {
        try {
            acc.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить auth.yml: " + e.getMessage());
        }
    }
}
