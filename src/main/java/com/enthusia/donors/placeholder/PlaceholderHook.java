package com.enthusia.donors.placeholder;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.config.ConfigManager;
import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.DonorEntry;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public final class PlaceholderHook extends PlaceholderExpansion {
    private final Plugin plugin;
    private final ConfigManager configManager;
    private final DonorCache cache;

    public PlaceholderHook(Plugin plugin, ConfigManager configManager, DonorCache cache) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.cache = cache;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "enthusiadonors";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Enthusia";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        DonorsConfig config = configManager.get();
        DonorCache.Snapshot snapshot = cache.snapshot();

        if (params.equalsIgnoreCase("alltime_count")) {
            return String.valueOf(snapshot.alltime().size());
        }
        if (params.equalsIgnoreCase("monthly_count")) {
            return String.valueOf(snapshot.monthly().size());
        }
        if (params.equalsIgnoreCase("last_updated")) {
            return snapshot.updatedAt() == null ? "Never" : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(snapshot.updatedAt().atOffset(ZoneOffset.UTC));
        }
        if (params.equalsIgnoreCase("status")) {
            return cache.state().name().toLowerCase();
        }

        if (params.startsWith("player_")) {
            return playerValue(player, params, snapshot, config);
        }

        String[] parts = params.split("_");
        if (parts.length == 5 && parts[1].equalsIgnoreCase("top")) {
            String board = parts[0].toLowerCase();
            int rank;
            try {
                rank = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ex) {
                return "";
            }
            if (rank < 1 || rank > config.topSize()) {
                return "";
            }
            Optional<DonorEntry> entry = board.equals("alltime") ? snapshot.topAlltime(rank) : snapshot.topMonthly(rank);
            return entry.map(d -> donorField(d, board.equals("alltime"), parts[3] + "_" + parts[4], config))
                    .orElseGet(() -> emptyField(parts[3] + "_" + parts[4], config));
        }
        if (parts.length == 4 && parts[1].equalsIgnoreCase("top")) {
            String board = parts[0].toLowerCase();
            int rank;
            try {
                rank = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ex) {
                return "";
            }
            Optional<DonorEntry> entry = board.equals("alltime") ? snapshot.topAlltime(rank) : snapshot.topMonthly(rank);
            return entry.map(d -> donorField(d, board.equals("alltime"), parts[3], config))
                    .orElseGet(() -> emptyField(parts[3], config));
        }

        return "";
    }

    private String playerValue(OfflinePlayer player, String params, DonorCache.Snapshot snapshot, DonorsConfig config) {
        Optional<DonorEntry> entry = player == null ? Optional.empty() : snapshot.byUuid(player.getUniqueId());
        return switch (params.toLowerCase()) {
            case "player_alltime_amount" -> entry.map(d -> cache.formatAmount(d.alltimeTotal(), config)).orElse(config.emptyAmount());
            case "player_alltime_amount_raw" -> entry.map(d -> cache.rawAmount(d.alltimeTotal())).orElse("0.00");
            case "player_alltime_rank" -> entry.map(d -> String.valueOf(d.alltimeRank())).orElse(config.emptyRank());
            case "player_monthly_amount" -> entry.map(d -> cache.formatAmount(d.monthlyTotal(), config)).orElse(config.emptyAmount());
            case "player_monthly_amount_raw" -> entry.map(d -> cache.rawAmount(d.monthlyTotal())).orElse("0.00");
            case "player_monthly_rank" -> entry.filter(d -> d.monthlyRank() > 0).map(d -> String.valueOf(d.monthlyRank())).orElse(config.emptyRank());
            default -> "";
        };
    }

    private String donorField(DonorEntry donor, boolean alltime, String field, DonorsConfig config) {
        return switch (field.toLowerCase()) {
            case "name" -> donor.name();
            case "uuid" -> donor.uuid().toString();
            case "amount" -> cache.formatAmount(alltime ? donor.alltimeTotal() : donor.monthlyTotal(), config);
            case "amount_raw" -> cache.rawAmount(alltime ? donor.alltimeTotal() : donor.monthlyTotal());
            case "rank" -> String.valueOf(alltime ? donor.alltimeRank() : donor.monthlyRank());
            default -> "";
        };
    }

    private String emptyField(String field, DonorsConfig config) {
        return switch (field.toLowerCase()) {
            case "name" -> config.emptyName();
            case "uuid" -> "";
            case "amount" -> config.emptyAmount();
            case "amount_raw" -> "0.00";
            case "rank" -> config.emptyRank();
            default -> "";
        };
    }
}
