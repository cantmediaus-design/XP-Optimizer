package com.xpoptimizer.command;

import com.xpoptimizer.XPConfig;
import com.xpoptimizer.XPOptimizerPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;

@SuppressWarnings("UnstableApiUsage")
public class XPStatsCommand implements BasicCommand {

    private final XPOptimizerPlugin plugin;

    public XPStatsCommand(XPOptimizerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();
        XPConfig cfg = plugin.getXPConfig();

        if (args.length == 0) {
            handleStatsSelf(sender, cfg);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender, cfg);
            case "top" -> handleTop(sender, cfg, args);
            case "reset" -> handleReset(sender, cfg, args);
            default -> handleStatsOther(sender, cfg, args[0]);
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("xpoptimizer.reload")) completions.add("reload");
            if (sender.hasPermission("xpoptimizer.stats")) completions.add("top");
            if (sender.hasPermission("xpoptimizer.reset")) completions.add("reset");
            if (sender.hasPermission("xpoptimizer.stats.others")) {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
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
                return plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .toList();
            }
        }

        return List.of();
    }

    private void handleStatsSelf(CommandSender sender, XPConfig cfg) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(cfg.formatMessage("console-stats-denied"));
            return;
        }
        if (!player.hasPermission("xpoptimizer.stats")) {
            player.sendMessage(cfg.formatMessage("no-permission"));
            return;
        }
        long total = plugin.getXpStat(player.getUniqueId());
        player.sendMessage(cfg.formatMessage("stats-self", "%xp%", String.format("%,d", total)));
    }

    private void handleReload(CommandSender sender, XPConfig cfg) {
        if (!sender.hasPermission("xpoptimizer.reload")) {
            sender.sendMessage(cfg.formatMessage("no-permission"));
            return;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage(plugin.getXPConfig().formatMessage("reload-success"));
    }

    private void handleTop(CommandSender sender, XPConfig cfg, String[] args) {
        if (!sender.hasPermission("xpoptimizer.stats")) {
            sender.sendMessage(cfg.formatMessage("no-permission"));
            return;
        }

        int count = 10;
        if (args.length >= 2) {
            try {
                count = Math.clamp(Integer.parseInt(args[1]), 1, 100);
            } catch (NumberFormatException ignored) {}
        }

        List<Map.Entry<UUID, Long>> sorted = plugin.getXpStatsEntries().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(count)
                .toList();

        sender.sendMessage(cfg.formatMessage("top-header", "%count%", String.valueOf(sorted.size())));
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : sorted) {
            Player online = plugin.getServer().getPlayer(entry.getKey());
            String name = online != null ? online.getName() : entry.getKey().toString();
            sender.sendMessage(cfg.formatMessage("top-entry",
                    "%rank%", String.valueOf(rank),
                    "%player%", name,
                    "%xp%", String.format("%,d", entry.getValue())
            ));
            rank++;
        }
    }

    private void handleReset(CommandSender sender, XPConfig cfg, String[] args) {
        if (args.length == 1) {
            // Reset self
            if (!(sender instanceof Player player)) {
                sender.sendMessage(cfg.formatMessage("console-reset-denied"));
                return;
            }
            if (!player.hasPermission("xpoptimizer.reset")) {
                player.sendMessage(cfg.formatMessage("no-permission"));
                return;
            }
            plugin.resetXpStat(player.getUniqueId());
            player.sendMessage(cfg.formatMessage("reset-self"));
            return;
        }

        // Reset other player
        if (!sender.hasPermission("xpoptimizer.reset.others")) {
            sender.sendMessage(cfg.formatMessage("reset-no-permission"));
            return;
        }

        // Try fast online lookup first, fall back to offline
        Player online = plugin.getServer().getPlayerExact(args[1]);
        if (online != null) {
            plugin.resetXpStat(online.getUniqueId());
            sender.sendMessage(cfg.formatMessage("reset-other", "%player%", online.getName()));
            return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = plugin.getServer().getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore()) {
            sender.sendMessage(cfg.formatMessage("stats-player-not-found"));
            return;
        }
        plugin.resetXpStat(target.getUniqueId());
        String name = target.getName() != null ? target.getName() : args[1];
        sender.sendMessage(cfg.formatMessage("reset-other", "%player%", name));
    }

    private void handleStatsOther(CommandSender sender, XPConfig cfg, String playerName) {
        if (!sender.hasPermission("xpoptimizer.stats.others")) {
            sender.sendMessage(cfg.formatMessage("no-permission"));
            return;
        }

        // Try fast online lookup first, fall back to offline (may hit disk)
        Player online = plugin.getServer().getPlayerExact(playerName);
        if (online != null) {
            long total = plugin.getXpStat(online.getUniqueId());
            sender.sendMessage(cfg.formatMessage("stats-other", "%player%", online.getName(), "%xp%", String.format("%,d", total)));
            return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = plugin.getServer().getOfflinePlayer(playerName);
        if (!target.hasPlayedBefore()) {
            sender.sendMessage(cfg.formatMessage("stats-player-not-found"));
            sender.sendMessage(cfg.formatMessage("usage"));
            return;
        }

        long total = plugin.getXpStat(target.getUniqueId());
        String name = target.getName() != null ? target.getName() : playerName;
        sender.sendMessage(cfg.formatMessage("stats-other", "%player%", name, "%xp%", String.format("%,d", total)));
    }
}
