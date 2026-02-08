package com.xpoptimizer;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.logging.Logger;

public record XPConfig(
        boolean enabled,
        double range,
        double rangeSq,
        double multiplier,
        boolean statsEnabled,
        int autoSaveIntervalSeconds,
        boolean soundEnabled,
        Sound sound,
        float soundVolume,
        float soundPitch,
        boolean particlesEnabled,
        Particle particle,
        int particleCount,
        long effectCooldownMs,
        WorldFilterMode worldFilterMode,
        Set<String> worldFilterList,
        boolean debug,
        Map<String, String> messages
) {

    public enum WorldFilterMode { DISABLED, WHITELIST, BLACKLIST }

    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("reload-success", "&a[XPOptimizer] Config reloaded."),
            Map.entry("no-permission", "&c[XPOptimizer] You do not have permission."),
            Map.entry("stats-self", "&6[XPOptimizer] &fTotal XP collected: &a%xp%"),
            Map.entry("stats-other", "&6[XPOptimizer] &f%player%'s total XP: &a%xp%"),
            Map.entry("stats-player-not-found", "&c[XPOptimizer] Player not found."),
            Map.entry("console-stats-denied", "&c[XPOptimizer] Specify a player: /xpstats <player>"),
            Map.entry("top-header", "&6[XPOptimizer] &f--- Top %count% XP Collectors ---"),
            Map.entry("top-entry", "&6%rank%. &f%player% &7- &a%xp%"),
            Map.entry("reset-self", "&a[XPOptimizer] Your XP stats have been reset."),
            Map.entry("reset-other", "&a[XPOptimizer] Reset XP stats for %player%."),
            Map.entry("reset-no-permission", "&c[XPOptimizer] You cannot reset other players' stats."),
            Map.entry("usage", "&6Usage: /xpstats [reload|top|reset|<player>]")
    );

    public boolean isWorldAllowed(String worldName) {
        return switch (worldFilterMode) {
            case DISABLED -> true;
            case WHITELIST -> worldFilterList.contains(worldName);
            case BLACKLIST -> !worldFilterList.contains(worldName);
        };
    }

    public String formatMessage(String key, String... replacements) {
        String msg = messages.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key, key));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return translateColors(msg);
    }

    private static String translateColors(String msg) {
        char[] chars = msg.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(chars[i + 1]) != -1) {
                chars[i] = '\u00A7';
            }
        }
        return new String(chars);
    }

    public static XPConfig fromBukkitConfig(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("enabled", true);

        double range = config.getDouble("range", 16.0);
        if (range <= 0) {
            logger.warning("Invalid range '" + range + "', using default 16.0");
            range = 16.0;
        }

        double multiplier = config.getDouble("xp-multiplier", 1.0);
        if (multiplier < 0) {
            logger.warning("Invalid xp-multiplier '" + multiplier + "', using default 1.0");
            multiplier = 1.0;
        }

        boolean statsEnabled = config.getBoolean("stats.enabled", true);

        int autoSaveInterval = config.getInt("stats.auto-save-interval", 300);
        if (autoSaveInterval > 0 && autoSaveInterval < 10) {
            logger.warning("stats.auto-save-interval too low (" + autoSaveInterval + "), clamping to 10");
            autoSaveInterval = 10;
        }

        // Sound
        boolean soundEnabled = config.getBoolean("sound.enabled", false);
        Sound sound = null;
        float soundVolume = 0.5f;
        float soundPitch = 1.0f;
        if (soundEnabled) {
            String name = config.getString("sound.type", "entity.experience_orb.pickup");
            if (name != null) {
                sound = Registry.SOUNDS.get(NamespacedKey.minecraft(name.toLowerCase()));
            }
            if (sound == null) {
                logger.warning("Invalid sound type '" + name + "', disabling sound effects");
                soundEnabled = false;
            }
            soundVolume = (float) config.getDouble("sound.volume", 0.5);
            if (soundVolume < 0 || soundVolume > 2.0f) {
                logger.warning("sound.volume '" + soundVolume + "' out of range [0, 2.0], clamping");
                soundVolume = Math.clamp(soundVolume, 0f, 2.0f);
            }
            soundPitch = (float) config.getDouble("sound.pitch", 1.0);
            if (soundPitch < 0.5f || soundPitch > 2.0f) {
                logger.warning("sound.pitch '" + soundPitch + "' out of range [0.5, 2.0], clamping");
                soundPitch = Math.clamp(soundPitch, 0.5f, 2.0f);
            }
        }

        // Particles
        boolean particlesEnabled = config.getBoolean("particles.enabled", false);
        Particle particle = null;
        int particleCount = 5;
        if (particlesEnabled) {
            String particleName = config.getString("particles.type", "HAPPY_VILLAGER");
            try {
                particle = Particle.valueOf(particleName);
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid particle type '" + particleName + "', disabling particles");
                particlesEnabled = false;
            }
            particleCount = config.getInt("particles.count", 5);
            if (particleCount <= 0) {
                logger.warning("particles.count must be > 0, using default 5");
                particleCount = 5;
            }
        }

        // Effect cooldown
        long effectCooldownMs = config.getLong("effect-cooldown-ms", 200);
        if (effectCooldownMs < 0) {
            logger.warning("effect-cooldown-ms cannot be negative, using 0");
            effectCooldownMs = 0;
        }

        // Debug
        boolean debug = config.getBoolean("debug", false);

        // World filter
        WorldFilterMode worldFilterMode = WorldFilterMode.DISABLED;
        String modeStr = config.getString("world-filter.mode", "DISABLED");
        try {
            worldFilterMode = WorldFilterMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid world-filter.mode '" + modeStr + "', using DISABLED");
        }
        Set<String> worldFilterList = Set.copyOf(config.getStringList("world-filter.worlds"));

        // Messages
        Map<String, String> messages = new HashMap<>(DEFAULT_MESSAGES);
        if (config.isConfigurationSection("messages")) {
            for (String key : config.getConfigurationSection("messages").getKeys(false)) {
                String value = config.getString("messages." + key);
                if (value != null) {
                    messages.put(key, value);
                }
            }
        }
        messages = Map.copyOf(messages);

        return new XPConfig(
                enabled, range, range * range, multiplier,
                statsEnabled, autoSaveInterval,
                soundEnabled, sound, soundVolume, soundPitch,
                particlesEnabled, particle, particleCount,
                effectCooldownMs,
                worldFilterMode, worldFilterList,
                debug, messages
        );
    }
}
