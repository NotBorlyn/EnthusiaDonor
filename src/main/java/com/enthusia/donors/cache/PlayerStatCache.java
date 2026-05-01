package com.enthusia.donors.cache;

import com.enthusia.donors.model.PlayerStatEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PlayerStatCache {
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public void replace(List<PlayerStatEntry> stats, Instant updatedAt) {
        List<PlayerStatEntry> kills = stats.stream()
                .filter(entry -> entry.kills() > 0)
                .sorted(java.util.Comparator.comparing(PlayerStatEntry::killsRank))
                .toList();
        List<PlayerStatEntry> deaths = stats.stream()
                .filter(entry -> entry.deaths() > 0)
                .sorted(java.util.Comparator.comparing(PlayerStatEntry::deathsRank))
                .toList();
        Map<UUID, PlayerStatEntry> byUuid = stats.stream()
                .collect(Collectors.toUnmodifiableMap(PlayerStatEntry::uuid, Function.identity(), (a, b) -> a));
        snapshot.set(new Snapshot(kills, deaths, byUuid, updatedAt));
    }

    public record Snapshot(
            List<PlayerStatEntry> kills,
            List<PlayerStatEntry> deaths,
            Map<UUID, PlayerStatEntry> byUuid,
            Instant updatedAt
    ) {
        public static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), Map.of(), null);
        }

        public Optional<PlayerStatEntry> topKills(int rank) {
            return rank > 0 && rank <= kills.size() ? Optional.of(kills.get(rank - 1)) : Optional.empty();
        }

        public Optional<PlayerStatEntry> topDeaths(int rank) {
            return rank > 0 && rank <= deaths.size() ? Optional.of(deaths.get(rank - 1)) : Optional.empty();
        }
    }
}
