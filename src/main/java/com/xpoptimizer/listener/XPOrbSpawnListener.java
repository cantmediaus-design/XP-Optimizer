package com.xpoptimizer.listener;

import com.xpoptimizer.XPConfig;
import com.xpoptimizer.XPOptimizerPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class XPOrbSpawnListener implements Listener {

    private final XPOptimizerPlugin plugin;
    private final Map<UUID, Long> lastEffectTime = new ConcurrentHashMap<>();

    public XPOrbSpawnListener(XPOptimizerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onXPOrbSpawn(EntitySpawnEvent event) {
        XPConfig cfg = plugin.getXPConfig();

        if (!cfg.enabled()) return;
        if (!(event.getEntity() instanceof ExperienceOrb orb)) return;
        if (!cfg.isWorldAllowed(orb.getWorld().getName())) return;

        Location orbLoc = orb.getLocation();
        Collection<Player> nearby = orbLoc.getNearbyPlayers(cfg.range());
        if (nearby.isEmpty()) return;

        double ox = orbLoc.getX(), oy = orbLoc.getY(), oz = orbLoc.getZ();

        Player closest = null;
        double minDistSq = cfg.rangeSq();
        for (Player player : nearby) {
            if (player.getGameMode() == GameMode.SPECTATOR) continue;

            double dx = player.getX() - ox;
            double dy = player.getY() - oy;
            double dz = player.getZ() - oz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < minDistSq) {
                minDistSq = distSq;
                closest = player;
            }
        }
        if (closest == null) return;

        int rawXp = orb.getExperience();
        double totalMultiplier = cfg.multiplier() * plugin.getPlayerBoost(closest.getUniqueId());
        int xp = totalMultiplier != 1.0 ? (int) Math.round(rawXp * totalMultiplier) : rawXp;
        if (xp <= 0) return;

        event.setCancelled(true);
        closest.giveExp(xp, true);

        if (cfg.statsEnabled()) {
            plugin.addXpStat(closest.getUniqueId(), xp);
        }

        if (canPlayEffects(closest.getUniqueId(), cfg.effectCooldownMs())) {
            if (cfg.soundEnabled()) {
                closest.playSound(orbLoc, cfg.sound(), cfg.soundVolume(), cfg.soundPitch());
            }
            if (cfg.particlesEnabled()) {
                closest.spawnParticle(cfg.particle(), orbLoc, cfg.particleCount());
            }
        }

        if (cfg.debug()) {
            plugin.getLogger().info("[Debug] %s received %d XP (raw: %d) at %s".formatted(
                    closest.getName(), xp, rawXp, orbLoc.toVector()));
        }
    }

    public void cleanupEffectTimes() {
        if (lastEffectTime.isEmpty()) return;
        long cutoff = System.currentTimeMillis() - 60_000; // 1 minute
        lastEffectTime.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    private boolean canPlayEffects(UUID playerId, long cooldownMs) {
        if (cooldownMs <= 0) return true;
        long now = System.currentTimeMillis();
        Long last = lastEffectTime.get(playerId);
        if (last != null && (now - last) < cooldownMs) return false;
        lastEffectTime.put(playerId, now);
        return true;
    }
}
