package com.enthusia.donors.cache;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.model.RefreshState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DonorCache {
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private volatile RefreshState state = RefreshState.STARTING;
    private volatile String lastError = "";
    private volatile Instant lastRefreshAttempt;
    private volatile Instant lastSuccessfulRefresh;

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public void replace(List<DonorEntry> donors, Instant updatedAt, RefreshState newState) {
        List<DonorEntry> alltime = donors.stream()
                .filter(d -> d.alltimeTotal().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(DonorEntry::alltimeRank))
                .toList();
        List<DonorEntry> monthly = donors.stream()
                .filter(d -> d.monthlyTotal().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(DonorEntry::monthlyRank))
                .toList();
        Map<UUID, DonorEntry> byUuid = donors.stream()
                .collect(Collectors.toUnmodifiableMap(DonorEntry::uuid, Function.identity(), (a, b) -> a));
        Map<String, DonorEntry> byName = donors.stream()
                .collect(Collectors.toUnmodifiableMap(d -> d.name().toLowerCase(), Function.identity(), (a, b) -> a));

        snapshot.set(new Snapshot(alltime, monthly, byUuid, byName, updatedAt));
        lastSuccessfulRefresh = updatedAt;
        state = newState;
        lastError = "";
    }

    public void markAttempt() {
        lastRefreshAttempt = Instant.now();
        state = RefreshState.REFRESHING;
    }

    public void markFailure(String safeMessage) {
        state = RefreshState.TEBEX_FAILED;
        lastError = safeMessage == null ? "" : safeMessage;
    }

    public void markCacheOnly() {
        state = RefreshState.CACHE_ONLY;
    }

    public void markNotConfigured() {
        state = RefreshState.TEBEX_NOT_CONFIGURED;
    }

    public RefreshState state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    public Instant lastRefreshAttempt() {
        return lastRefreshAttempt;
    }

    public Instant lastSuccessfulRefresh() {
        return lastSuccessfulRefresh;
    }

    public String formatAmount(BigDecimal amount, DonorsConfig config) {
        if (amount == null) {
            return config.emptyAmount();
        }
        int scale = config.showCents() ? 2 : 0;
        BigDecimal normalized = amount.setScale(scale, RoundingMode.HALF_UP);
        return config.currencySymbol() + normalized.toPlainString();
    }

    public String rawAmount(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public record Snapshot(
            List<DonorEntry> alltime,
            List<DonorEntry> monthly,
            Map<UUID, DonorEntry> byUuid,
            Map<String, DonorEntry> byName,
            Instant updatedAt
    ) {
        public static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), Map.of(), Map.of(), null);
        }

        public Optional<DonorEntry> topAlltime(int rank) {
            return rank > 0 && rank <= alltime.size() ? Optional.of(alltime.get(rank - 1)) : Optional.empty();
        }

        public Optional<DonorEntry> topMonthly(int rank) {
            return rank > 0 && rank <= monthly.size() ? Optional.of(monthly.get(rank - 1)) : Optional.empty();
        }

        public Optional<DonorEntry> byUuid(UUID uuid) {
            return Optional.ofNullable(byUuid.get(uuid));
        }

        public Optional<DonorEntry> byName(String name) {
            return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name.toLowerCase()));
        }
    }
}
