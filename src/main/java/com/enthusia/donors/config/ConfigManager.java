package com.enthusia.donors.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConfigManager {
    private final JavaPlugin plugin;
    private volatile DonorsConfig config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        ZoneId zone;
        String zoneName = c.getString("leaderboards.timezone", "America/Chicago");
        try {
            zone = ZoneId.of(zoneName);
        } catch (DateTimeException ex) {
            plugin.getLogger().warning("Invalid leaderboards.timezone '" + zoneName + "', using America/Chicago.");
            zone = ZoneId.of("America/Chicago");
        }

        Path jsonPath = plugin.getServer().getWorldContainer().toPath()
                .resolve(c.getString("cache.json-export-path", "plugins/EnthusiaDonors/public/donors.json"))
                .normalize();

        config = new DonorsConfig(
                c.getString("tebex.api-key", ""),
                Math.max(1, c.getInt("tebex.refresh-interval-minutes", 10)),
                Math.max(1, c.getInt("tebex.timeout-seconds", 10)),
                Math.max(0, c.getInt("tebex.max-retries", 3)),
                Math.max(1, c.getInt("tebex.max-pages", 1000)),
                c.getString("counting.source", "tebex_payments_only"),
                c.getBoolean("counting.count-zero-dollar-payments", false),
                c.getBoolean("counting.count-manual-payments", false),
                c.getBoolean("counting.count-refunded-payments", false),
                c.getBoolean("counting.count-chargeback-payments", false),
                integerSet(c, "counting.included-package-ids"),
                integerSet(c, "counting.excluded-package-ids"),
                excludedTransactionIds(c),
                excludedPaymentHashes(c),
                zone,
                c.getString("leaderboards.currency-symbol", "$"),
                c.getBoolean("leaderboards.show-cents", true),
                Math.max(1, c.getInt("leaderboards.top-size", 10)),
                c.getString("leaderboards.empty-name", "None"),
                c.getString("leaderboards.empty-amount", "$0.00"),
                c.getString("leaderboards.empty-rank", "-"),
                c.getBoolean("cache.use-sqlite", true),
                c.getBoolean("cache.save-json-export", false),
                jsonPath,
                c.getBoolean("r2.enabled", false),
                c.getString("r2.account-id", ""),
                c.getString("r2.endpoint", ""),
                c.getString("r2.access-key-id", ""),
                c.getString("r2.secret-access-key", ""),
                c.getString("r2.bucket", "donator-leaderboard"),
                c.getString("r2.object-path", "leaderboards/donators-all-time.json"),
                c.getString("r2.index-object-path", "leaderboards/index.json"),
                c.getBoolean("privacy.expose-raw-payment-data", false),
                c.getBoolean("privacy.hash-payment-ids", true),
                c.getBoolean("testing.fake-data-enabled", false),
                Math.max(1, c.getInt("testing.fake-player-count", 12))
        );

        if (config.exposeRawPaymentData()) {
            plugin.getLogger().warning("privacy.expose-raw-payment-data is ignored; raw payment data is never exported.");
        }
        if (!config.hashPaymentIds()) {
            plugin.getLogger().warning("privacy.hash-payment-ids=false is ignored; payment IDs are always hashed.");
        }
        if (!config.useSqlite()) {
            plugin.getLogger().warning("cache.use-sqlite=false is ignored in this build; SQLite is used for reliable local caching.");
        }
        if (!"tebex_payments_only".equalsIgnoreCase(config.countingSource())) {
            plugin.getLogger().warning("counting.source is forced to tebex_payments_only; ranks, groups, and permissions are never used for donor totals.");
        }
        if (config.r2UploadEnabled() && !r2Configured(config)) {
            plugin.getLogger().warning("r2.enabled=true but R2 credentials are incomplete; donor leaderboard upload will be skipped.");
        }
    }

    public DonorsConfig get() {
        return config;
    }

    private Set<Integer> integerSet(FileConfiguration c, String path) {
        return c.getIntegerList(path).stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> excludedPaymentHashes(FileConfiguration c) {
        return excludedTransactionIds(c).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::hashOrNormalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> excludedTransactionIds(FileConfiguration c) {
        return c.getStringList("counting.excluded-transaction-ids").stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String hashOrNormalize(String value) {
        if (value.matches("(?i)^[0-9a-f]{64}$")) {
            return value.toLowerCase();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash excluded Tebex transaction ID", ex);
        }
    }

    private boolean r2Configured(DonorsConfig config) {
        boolean hasEndpoint = !blank(config.r2Endpoint()) || !blank(config.r2AccountId());
        return hasEndpoint
                && !blank(config.r2AccessKeyId())
                && !blank(config.r2SecretAccessKey())
                && !blank(config.r2Bucket())
                && !blank(config.r2ObjectPath());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
