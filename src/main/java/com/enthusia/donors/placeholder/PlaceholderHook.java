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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceholderHook extends PlaceholderExpansion {
    private static final Pattern HEX_TAG = Pattern.compile("^</?#([0-9a-fA-F]{6})>$");
    private static final Pattern RAW_HEX = Pattern.compile("^#([0-9a-fA-F]{6})$");
    private static final Pattern AMP_HEX = Pattern.compile("&?#([0-9a-fA-F]{6})");
    private static final String COLOR_PREFIX = "color_";

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
        ColorRequest colorRequest = extractColor(params);
        if (colorRequest != null) {
            return colorRequest.colorCode() + resolvePlaceholder(player, colorRequest.remainingParams());
        }
        return resolvePlaceholder(player, params);
    }

    private String resolvePlaceholder(OfflinePlayer player, String params) {
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

    private ColorRequest extractColor(String params) {
        if (!params.toLowerCase().startsWith(COLOR_PREFIX)) {
            return null;
        }
        String withoutPrefix = params.substring(COLOR_PREFIX.length());
        int delimiter = withoutPrefix.indexOf('_');
        if (delimiter <= 0 || delimiter == withoutPrefix.length() - 1) {
            return null;
        }
        String rawColor = withoutPrefix.substring(0, delimiter);
        String colorCode = parseColor(rawColor);
        if (colorCode.isEmpty()) {
            return null;
        }
        return new ColorRequest(colorCode, withoutPrefix.substring(delimiter + 1));
    }

    private String parseColor(String rawColor) {
        String color = rawColor.trim();
        Matcher hexTag = HEX_TAG.matcher(color);
        if (hexTag.matches()) {
            return legacyHex(hexTag.group(1));
        }
        Matcher rawHex = RAW_HEX.matcher(color);
        if (rawHex.matches()) {
            return legacyHex(rawHex.group(1));
        }
        Matcher ampHex = AMP_HEX.matcher(color);
        if (ampHex.matches()) {
            return legacyHex(ampHex.group(1));
        }
        return translateLegacyCodes(color);
    }

    private String translateLegacyCodes(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '&' && i + 1 < value.length() && isLegacyCode(value.charAt(i + 1))) {
                result.append('§').append(Character.toLowerCase(value.charAt(i + 1)));
                i++;
                continue;
            }
            if (current == '§' && i + 1 < value.length() && isLegacyCode(value.charAt(i + 1))) {
                result.append('§').append(Character.toLowerCase(value.charAt(i + 1)));
                i++;
                continue;
            }
            result.append(current);
        }
        return result.toString();
    }

    private boolean isLegacyCode(char code) {
        return "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(code) >= 0;
    }

    private String legacyHex(String hex) {
        StringBuilder result = new StringBuilder("§x");
        for (char c : hex.toLowerCase().toCharArray()) {
            result.append('§').append(c);
        }
        return result.toString();
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

    private record ColorRequest(String colorCode, String remainingParams) {
    }
}
