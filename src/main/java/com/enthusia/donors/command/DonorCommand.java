package com.enthusia.donors.command;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.config.ConfigManager;
import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.service.LeaderboardService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DonorCommand implements CommandExecutor, TabCompleter {
    private final ConfigManager configManager;
    private final DonorCache cache;
    private final LeaderboardService service;
    private final Runnable reloadAction;

    public DonorCommand(ConfigManager configManager, DonorCache cache, LeaderboardService service, Runnable reloadAction) {
        this.configManager = configManager;
        this.cache = cache;
        this.service = service;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/enthusiadonors status §7| reload | refresh | top <alltime|monthly> | debug <player>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!require(sender, "enthusiadonors.reload")) return true;
                reloadAction.run();
                sender.sendMessage("§aEnthusiaDonors config reloaded.");
            }
            case "refresh" -> {
                if (!require(sender, "enthusiadonors.refresh")) return true;
                service.refresh(true);
                sender.sendMessage("§aStarted async donor refresh.");
            }
            case "status" -> {
                if (!require(sender, "enthusiadonors.status")) return true;
                sendStatus(sender);
            }
            case "top" -> {
                if (!require(sender, "enthusiadonors.status")) return true;
                sendTop(sender, args.length > 1 ? args[1] : "alltime");
            }
            case "debug" -> {
                if (!require(sender, "enthusiadonors.debug")) return true;
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /enthusiadonors debug <player>");
                    return true;
                }
                sendDebug(sender, args[1]);
            }
            default -> sender.sendMessage("§cUnknown subcommand.");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        DonorCache.Snapshot snapshot = cache.snapshot();
        sender.sendMessage("§6EnthusiaDonors Status");
        sender.sendMessage("§7State: §f" + cache.state().name().toLowerCase());
        sender.sendMessage("§7All-time donors: §f" + snapshot.alltime().size());
        sender.sendMessage("§7Monthly donors: §f" + snapshot.monthly().size());
        sender.sendMessage("§7Last attempt: §f" + format(cache.lastRefreshAttempt()));
        sender.sendMessage("§7Last success: §f" + format(cache.lastSuccessfulRefresh()));
        if (!cache.lastError().isBlank()) {
            sender.sendMessage("§7Last error: §f" + cache.lastError());
        }
    }

    private void sendTop(CommandSender sender, String board) {
        DonorsConfig config = configManager.get();
        boolean alltime = !board.equalsIgnoreCase("monthly");
        List<DonorEntry> donors = alltime ? cache.snapshot().alltime() : cache.snapshot().monthly();
        sender.sendMessage("§6Top " + Math.min(config.topSize(), 10) + " " + (alltime ? "all-time" : "monthly") + " donors");
        if (donors.isEmpty()) {
            sender.sendMessage("§7No donors cached.");
            return;
        }
        donors.stream().limit(Math.min(config.topSize(), 10)).forEach(d -> {
            String amount = cache.formatAmount(alltime ? d.alltimeTotal() : d.monthlyTotal(), config);
            int rank = alltime ? d.alltimeRank() : d.monthlyRank();
            sender.sendMessage("§e#" + rank + " §f" + d.name() + " §7- §a" + amount);
        });
    }

    private void sendDebug(CommandSender sender, String name) {
        DonorsConfig config = configManager.get();
        @SuppressWarnings("deprecation")
        OfflinePlayer player = Bukkit.getOfflinePlayer(name);
        Optional<DonorEntry> donor = cache.snapshot().byUuid(player.getUniqueId()).or(() -> cache.snapshot().byName(name));
        if (donor.isEmpty()) {
            sender.sendMessage("§eNo cached donor totals for " + name + ".");
            return;
        }
        DonorEntry d = donor.get();
        sender.sendMessage("§6Donor cache for " + d.name());
        sender.sendMessage("§7UUID: §f" + d.uuid());
        sender.sendMessage("§7All-time: §a" + cache.formatAmount(d.alltimeTotal(), config) + " §7rank §f#" + d.alltimeRank());
        sender.sendMessage("§7Monthly: §a" + cache.formatAmount(d.monthlyTotal(), config) + " §7rank §f" + (d.monthlyRank() > 0 ? "#" + d.monthlyRank() : config.emptyRank()));
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("enthusiadonors.admin")) {
            return true;
        }
        sender.sendMessage("§cYou do not have permission.");
        return false;
    }

    private String format(java.time.Instant instant) {
        return instant == null ? "Never" : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "refresh", "status", "top", "debug"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            return filter(List.of("alltime", "monthly"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(prefix.toLowerCase())) {
                result.add(value);
            }
        }
        return result;
    }
}
