package com.enthusia.donors.placeholder;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.cache.PlayerStatCache;
import com.enthusia.donors.config.ConfigManager;
import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.model.PlayerStatEntry;
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
    private final PlayerStatCache playerStatCache;

    public PlaceholderHook(Plugin plugin, ConfigManager configManager, DonorCache cache, PlayerStatCache playerStatCache) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.cache = cache;
        this.playerStatCache = playerStatCache;
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
        if (params.equalsIgnoreCase("kills_count")) {
            return String.valueOf(playerStatCache.snapshot().kills().size());
        }
        if (params.equalsIgnoreCase("deaths_count")) {
            return String.valueOf(playerStatCache.snapshot().deaths().size());
        }
        if (params.equalsIgnoreCase("last_updated")) {
            return snapshot.updatedAt() == null ? "Never" : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(snapshot.updatedAt().atOffset(ZoneOffset.UTC));
        }
        if (params.equalsIgnoreCase("stats_last_updated")) {
            return playerStatCache.snapshot().updatedAt() == null ? "Never" : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(playerStatCache.snapshot().updatedAt().atOffset(ZoneOffset.UTC));
        }
        if (params.equalsIgnoreCase("status")) {
            return cache.state().name().toLowerCase();
        }

        if (params.startsWith("player_")) {
            return playerValue(player, params, snapshot, config);
        }

        String[] parts = params.split("_");
        if (isStatTop(parts)) {
            return statTopValue(parts, config);
        }
        if (parts.length == 5 && parts[1].equalsIgnoreCase("top")) {
            String board = parts[0].toLowerCase();
            int rank = parseRank(parts[2]);
            if (rank < 1 || rank > config.topSize()) {
                return "";
            }
            Optional<DonorEntry> entry = board.equals("alltime") ? snapshot.topAlltime(rank) : snapshot.topMonthly(rank);
            return entry.map(d -> donorField(d, board.equals("alltime"), parts[3] + "_" + parts[4], config))
                    .orElseGet(() -> emptyField(parts[3] + "_" + parts[4], config));
        }
        if (parts.length == 4 && parts[1].equalsIgnoreCase("top")) {
            String board = parts[0].toLowerCase();
            int rank = parseRank(parts[2]);
            if (rank < 1 || rank > config.topSize()) {
                return "";
            }
            Optional<DonorEntry> entry = board.equals("alltime") ? snapshot.topAlltime(rank) : snapshot.topMonthly(rank);
            return entry.map(d -> donorField(d, board.equals("alltime"), parts[3], config))
                    .orElseGet(() -> emptyField(parts[3], config));
        }

        return "";
    }

    private boolean isStatTop(String[] parts) {
        return parts.length == 4
                && parts[1].equalsIgnoreCase("top")
                && (parts[0].equalsIgnoreCase("kills") || parts[0].equalsIgnoreCase("deaths"));
    }

    private String statTopValue(String[] parts, DonorsConfig config) {
        String board = parts[0].toLowerCase();
        int rank = parseRank(parts[2]);
        if (rank < 1 || rank > config.topSize()) {
            return "";
        }
        Optional<PlayerStatEntry> entry = board.equals("kills")
                ? playerStatCache.snapshot().topKills(rank)
                : playerStatCache.snapshot().topDeaths(rank);
        return entry.map(stat -> statField(stat, board, parts[3], config))
                .orElseGet(() -> emptyStatField(parts[3], config));
    }

    private String statField(PlayerStatEntry stat, String board, String field, DonorsConfig config) {
        return switch (field.toLowerCase()) {
            case "name" -> stat.name();
            case "uuid" -> stat.uuid().toString();
            case "kills" -> String.valueOf(stat.kills());
            case "deaths" -> String.valueOf(stat.deaths());
            case "value", "amount" -> board.equals("kills") ? String.valueOf(stat.kills()) : String.valueOf(stat.deaths());
            case "rank" -> board.equals("kills") ? String.valueOf(stat.killsRank()) : String.valueOf(stat.deathsRank());
            default -> "";
        };
    }

    private String emptyStatField(String field, DonorsConfig config) {
        return switch (field.toLowerCase()) {
            case "name" -> config.emptyName();
            case "uuid" -> "";
            case "kills", "deaths", "value", "amount" -> "0";
            case "rank" -> config.emptyRank();
            default -> "";
        };
    }

    private int parseRank(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return -1;
        }
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
