package com.xpoptimizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.xpoptimizer.listener.XPOrbSpawnListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class XPOptimizerPlugin extends JavaPlugin {

    public record BoostData(double multiplier, long expiresAtTick) {}

    private volatile XPConfig config;
    private final Map<UUID, Long> xpStats = new ConcurrentHashMap<>();
    private final Map<UUID, BoostData> playerBoosts = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STATS_TYPE = new TypeToken<Map<String, Long>>() {}.getType();
    private BukkitTask autoSaveTask;
    private BukkitTask boostCleanupTask;
    private XPOrbSpawnListener listener;

    public XPConfig getXPConfig() {
        return config;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();

        if (config.statsEnabled()) loadStats();

        listener = new XPOrbSpawnListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        if (getServer().getPluginManager().getPlugin("CrazyEnchantments") != null) {
            getLogger().info("CrazyEnchantments detected -- XP enchantment bonuses will be captured automatically.");
        }

        registerCommands();

        // Cleanup expired boosts every second
        boostCleanupTask = getServer().getScheduler().runTaskTimer(this, () -> {
            long currentTick = getServer().getCurrentTick();
            playerBoosts.entrySet().removeIf(e -> e.getValue().expiresAtTick() <= currentTick);
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (boostCleanupTask != null) {
            boostCleanupTask.cancel();
            boostCleanupTask = null;
        }
        if (config.statsEnabled()) saveStats();
    }

    public void reloadPluginConfig() {
        reloadConfig();
        config = XPConfig.fromBukkitConfig(getConfig(), getLogger());
        scheduleAutoSave();
    }

    public void addXpStat(UUID playerId, int amount) {
        xpStats.merge(playerId, (long) amount, Long::sum);
    }

    public long getXpStat(UUID playerId) {
        return xpStats.getOrDefault(playerId, 0L);
    }

    public void resetXpStat(UUID playerId) {
        xpStats.remove(playerId);
    }

    public Set<Map.Entry<UUID, Long>> getXpStatsEntries() {
        return xpStats.entrySet();
    }

    // --- Per-Player Boost API ---

    public void setPlayerBoost(UUID playerId, double multiplier, long durationTicks) {
        long expiresAt = getServer().getCurrentTick() + durationTicks;
        playerBoosts.put(playerId, new BoostData(multiplier, expiresAt));
    }

    public double getPlayerBoost(UUID playerId) {
        BoostData boost = playerBoosts.get(playerId);
        if (boost == null) return 1.0;
        if (boost.expiresAtTick() <= getServer().getCurrentTick()) {
            playerBoosts.remove(playerId);
            return 1.0;
        }
        return boost.multiplier();
    }

    public void clearPlayerBoost(UUID playerId) {
        playerBoosts.remove(playerId);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void registerCommands() {
        getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("xpstats",
                "View XP stats, leaderboards, or manage plugin config",
                new com.xpoptimizer.command.XPStatsCommand(this));
        });
    }

    // --- Auto Save ---

    private void scheduleAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (!config.statsEnabled() || config.autoSaveIntervalSeconds() <= 0) return;

        long intervalTicks = config.autoSaveIntervalSeconds() * 20L;
        autoSaveTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            saveStats();
            listener.cleanupEffectTimes();
        }, intervalTicks, intervalTicks);
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

    private void saveStats() {
        Map<UUID, Long> snapshot = Map.copyOf(xpStats);

        Map<String, Long> raw = new HashMap<>();
        snapshot.forEach((uuid, value) -> raw.put(uuid.toString(), value));

        synchronized (this) {
            File file = new File(getDataFolder(), "stats.json");
            getDataFolder().mkdirs();

            try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                gson.toJson(raw, writer);
            } catch (Exception e) {
                getLogger().warning("Failed to save XP stats: " + e.getMessage());
            }
        }
    }
}
