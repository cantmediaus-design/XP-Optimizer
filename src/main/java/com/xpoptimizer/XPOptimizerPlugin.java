package com.xpoptimizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.xpoptimizer.listener.XPOrbSpawnListener;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class XPOptimizerPlugin extends JavaPlugin {

    private volatile XPConfig config;
    private final Map<UUID, Long> xpStats = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STATS_TYPE = new TypeToken<Map<String, Long>>() {}.getType();
    private BukkitTask autoSaveTask;

    public XPConfig getXPConfig() {
        return config;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();

        if (config.statsEnabled()) loadStats();

        getServer().getPluginManager().registerEvents(new XPOrbSpawnListener(this), this);

        if (getServer().getPluginManager().getPlugin("CrazyEnchantments") != null) {
            getLogger().info("CrazyEnchantments detected -- XP enchantment bonuses will be captured automatically.");
        }

        scheduleAutoSave();
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (config.statsEnabled()) saveStats();
    }

    public void reloadPluginConfig() {
        reloadConfig();
        config = XPConfig.fromBukkitConfig(getConfig(), getLogger());
    }

    public void addXpStat(UUID playerId, int amount) {
        xpStats.merge(playerId, (long) amount, Long::sum);
    }

    public long getXpStat(UUID playerId) {
        return xpStats.getOrDefault(playerId, 0L);
    }

    public Map<UUID, Long> getXpStatsSnapshot() {
        return Map.copyOf(xpStats);
    }

    public void resetXpStat(UUID playerId) {
        xpStats.remove(playerId);
    }

    // --- Commands ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("xpstats")) return false;
        XPConfig cfg = config;

        if (args.length == 0) {
            return handleStatsSelf(sender, cfg);
        }

        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender, cfg);
            case "top" -> handleTop(sender, cfg, args);
            case "reset" -> handleReset(sender, cfg, args);
            default -> handleStatsOther(sender, cfg, args[0]);
        };
    }

    private boolean handleStatsSelf(CommandSender sender, XPConfig cfg) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg.formatMessage("console-stats-denied"));
            return true;
        }
        if (!player.hasPermission("xpoptimizer.stats")) {
            player.sendMessage(cfg.formatMessage("no-permission"));
            return true;
        }
        long total = xpStats.getOrDefault(player.getUniqueId(), 0L);
        player.sendMessage(cfg.formatMessage("stats-self", "%xp%", String.format("%,d", total)));
        return true;
    }

    private boolean handleReload(CommandSender sender, XPConfig cfg) {
        if (!sender.hasPermission("xpoptimizer.reload")) {
            sender.sendMessage(cfg.formatMessage("no-permission"));
            return true;
        }
        reloadPluginConfig();
        scheduleAutoSave();
        sender.sendMessage(config.formatMessage("reload-success"));
        return true;
    }

    private boolean handleTop(CommandSender sender, XPConfig cfg, String[] args) {
        if (!sender.hasPermission("xpoptimizer.stats")) {
            sender.sendMessage(cfg.formatMessage("no-permission"));
            return true;
        }

        int count = 10;
        if (args.length >= 2) {
            try {
                count = Math.clamp(Integer.parseInt(args[1]), 1, 100);
            } catch (NumberFormatException ignored) {}
        }

        Map<UUID, Long> snapshot = getXpStatsSnapshot();
        List<Map.Entry<UUID, Long>> sorted = snapshot.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(count)
                .toList();

        sender.sendMessage(cfg.formatMessage("top-header", "%count%", String.valueOf(sorted.size())));
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : sorted) {
            OfflinePlayer op = getServer().getOfflinePlayer(entry.getKey());
            String name = op.getName() != null ? op.getName() : entry.getKey().toString();
            sender.sendMessage(cfg.formatMessage("top-entry",
                    "%rank%", String.valueOf(rank),
                    "%player%", name,
                    "%xp%", String.format("%,d", entry.getValue())
            ));
            rank++;
        }
        return true;
    }

    private boolean handleReset(CommandSender sender, XPConfig cfg, String[] args) {
        if (args.length == 1) {
            // Reset self
            if (!(sender instanceof Player player)) {
                sender.sendMessage(cfg.formatMessage("console-stats-denied"));
                return true;
            }
            if (!player.hasPermission("xpoptimizer.reset")) {
                player.sendMessage(cfg.formatMessage("no-permission"));
                return true;
            }
            resetXpStat(player.getUniqueId());
            player.sendMessage(cfg.formatMessage("reset-self"));
            return true;
        }

        // Reset other player
        if (!sender.hasPermission("xpoptimizer.reset.others")) {
            sender.sendMessage(cfg.formatMessage("reset-no-permission"));
            return true;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = getServer().getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(cfg.formatMessage("stats-player-not-found"));
            return true;
        }
        resetXpStat(target.getUniqueId());
        String name = target.getName() != null ? target.getName() : args[1];
        sender.sendMessage(cfg.formatMessage("reset-other", "%player%", name));
        return true;
    }

    private boolean handleStatsOther(CommandSender sender, XPConfig cfg, String playerName) {
        if (!sender.hasPermission("xpoptimizer.stats.others")) {
            sender.sendMessage(cfg.formatMessage("no-permission"));
            return true;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = getServer().getOfflinePlayer(playerName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(cfg.formatMessage("stats-player-not-found"));
            return true;
        }

        long total = xpStats.getOrDefault(target.getUniqueId(), 0L);
        String name = target.getName() != null ? target.getName() : playerName;
        sender.sendMessage(cfg.formatMessage("stats-other", "%player%", name, "%xp%", String.format("%,d", total)));
        return true;
    }

    // --- Tab Completion ---

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("xpstats")) return List.of();

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("xpoptimizer.reload")) completions.add("reload");
            if (sender.hasPermission("xpoptimizer.stats")) completions.add("top");
            if (sender.hasPermission("xpoptimizer.reset")) completions.add("reset");
            if (sender.hasPermission("xpoptimizer.stats.others")) {
                for (Player p : getServer().getOnlinePlayers()) {
                    completions.add(p.getName());
                }
            }
            String prefix = args[0].toLowerCase();
            return completions.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("top")) {
                return List.of("5", "10", "25");
            }
            if (sub.equals("reset") && sender.hasPermission("xpoptimizer.reset.others")) {
                String prefix = args[1].toLowerCase();
                return getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .toList();
            }
        }

        return List.of();
    }

    // --- Auto Save ---

    private void scheduleAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (!config.statsEnabled() || config.autoSaveIntervalSeconds() <= 0) return;

        long intervalTicks = config.autoSaveIntervalSeconds() * 20L;
        autoSaveTask = getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveStats, intervalTicks, intervalTicks);
    }

    // --- Stats I/O ---

    private void loadStats() {
        File file = new File(getDataFolder(), "stats.json");
        if (!file.exists()) return;

        try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            Map<String, Long> raw = gson.fromJson(reader, STATS_TYPE);
            if (raw != null) {
                raw.forEach((key, value) -> {
                    try {
                        xpStats.put(UUID.fromString(key), value);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Skipping invalid UUID in stats.json: " + key);
                    }
                });
            }
        } catch (Exception e) {
            getLogger().warning("Failed to load XP stats: " + e.getMessage());
        }
    }

    private synchronized void saveStats() {
        File file = new File(getDataFolder(), "stats.json");
        getDataFolder().mkdirs();

        Map<String, Long> raw = new HashMap<>();
        xpStats.forEach((uuid, value) -> raw.put(uuid.toString(), value));

        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            gson.toJson(raw, writer);
        } catch (Exception e) {
            getLogger().warning("Failed to save XP stats: " + e.getMessage());
        }
    }
}
