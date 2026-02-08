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
            Map.entry("console-reset-denied", "&c[XPOptimizer] Specify a player: /xpstats reset <player>"),
            Map.entry("top-header", "&6[XPOptimizer] &f--- Top %count% XP Collectors ---"),
            Map.entry("top-entry", "&6%rank%. &f%player% &7- &a%xp%"),
            Map.entry("reset-self", "&a[XPOptimizer] Your XP stats have been reset."),
            Map.entry("reset-other", "&a[XPOptimizer] Reset XP stats for %player%."),
            Map.entry("reset-no-permission", "&c[XPOptimizer] You cannot reset other players' stats."),
            Map.entry("usage", "&6Usage: /xpstats [reload|top|reset|<player>]")
    );

    /** O(1) lookup table for valid color/formatting codes after '&'. */
    private static final boolean[] VALID_COLOR_CODES = new boolean[128];
    static {
        for (char c : "0123456789abcdefklmnorABCDEFKLMNOR".toCharArray()) {
            VALID_COLOR_CODES[c] = true;
        }
    }

    public boolean isWorldAllowed(String worldName) {
        return switch (worldFilterMode) {
            case DISABLED -> true;
            case WHITELIST -> worldFilterList.contains(worldName);
            case BLACKLIST -> !worldFilterList.contains(worldName);
        };
    }

    public String formatMessage(String key, String... replacements) {
        String msg = messages.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key, key));

        if (replacements.length >= 2) {
            // Build a map from the varargs pairs
            Map<String, String> replacementMap = new HashMap<>(replacements.length / 2 + 1, 1.0f);
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                replacementMap.put(replacements[i], replacements[i + 1]);
            }

            // Single-pass replacement: scan for %token% patterns
            StringBuilder sb = new StringBuilder(msg.length());
            int len = msg.length();
            int i = 0;
            while (i < len) {
                char c = msg.charAt(i);
                if (c == '%') {
                    // Look ahead for closing %
                    int close = msg.indexOf('%', i + 1);
                    if (close != -1) {
                        String token = msg.substring(i, close + 1); // includes both % delimiters
                        String replacement = replacementMap.get(token);
                        if (replacement != null) {
                            sb.append(replacement);
                            i = close + 1;
                            continue;
                        }
                    }
                }
                sb.append(c);
                i++;
            }
            msg = sb.toString();
        }

        return translateColors(msg);
    }

    private static String translateColors(String msg) {
        char[] chars = msg.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&') {
                char next = chars[i + 1];
                if (next < 128 && VALID_COLOR_CODES[next]) {
                    chars[i] = '\u00A7';
                }
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

        // Messages -- avoid allocation when no custom messages are defined
        Map<String, String> messages;
        if (config.isConfigurationSection("messages")) {
            Map<String, String> merged = new HashMap<>(DEFAULT_MESSAGES);
            for (String key : config.getConfigurationSection("messages").getKeys(false)) {
                String value = config.getString("messages." + key);
                if (value != null) {
                    merged.put(key, value);
                }
            }
            messages = Map.copyOf(merged);
        } else {
            messages = DEFAULT_MESSAGES;
        }

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
