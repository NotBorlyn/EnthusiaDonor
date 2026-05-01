package com.enthusia.donors.storage;

import com.enthusia.donors.model.PlayerStatEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class PlayerStatRepository {
    private final Path databasePath;

    public PlayerStatRepository(Path dataFolder) {
        this.databasePath = dataFolder.resolve("donors.db");
    }

    public void init() throws SQLException {
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (Exception ex) {
            throw new SQLException("Unable to create plugin data directory", ex);
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                      player_uuid TEXT PRIMARY KEY,
                      player_name TEXT NOT NULL,
                      kills INTEGER NOT NULL,
                      deaths INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    public void upsertStats(List<PlayerStatEntry> stats) throws SQLException {
        if (stats.isEmpty()) {
            return;
        }
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO player_stats (player_uuid, player_name, kills, deaths, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                      player_name=excluded.player_name,
                      kills=excluded.kills,
                      deaths=excluded.deaths,
                      updated_at=excluded.updated_at
                    """)) {
                for (PlayerStatEntry entry : stats) {
                    ps.setString(1, entry.uuid().toString());
                    ps.setString(2, entry.name());
                    ps.setInt(3, entry.kills());
                    ps.setInt(4, entry.deaths());
                    ps.setLong(5, entry.updatedAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
            c.commit();
        }
    }

    public void incrementDeath(UUID uuid, String name) throws SQLException {
        increment(uuid, name, 0, 1);
    }

    public void incrementKill(UUID uuid, String name) throws SQLException {
        increment(uuid, name, 1, 0);
    }

    private void increment(UUID uuid, String name, int kills, int deaths) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO player_stats (player_uuid, player_name, kills, deaths, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                  player_name=excluded.player_name,
                  kills=kills + excluded.kills,
                  deaths=deaths + excluded.deaths,
                  updated_at=excluded.updated_at
                """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setInt(3, kills);
            ps.setInt(4, deaths);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    public List<PlayerStatEntry> loadRankedStats() throws SQLException {
        List<MutableStats> stats = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT player_uuid, player_name, kills, deaths, updated_at
                FROM player_stats
                """)) {
            while (rs.next()) {
                stats.add(new MutableStats(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        rs.getInt("kills"),
                        rs.getInt("deaths"),
                        rs.getLong("updated_at")
                ));
            }
        }

        stats.stream()
                .filter(entry -> entry.kills > 0)
                .sorted(Comparator.comparingInt((MutableStats entry) -> entry.kills).reversed())
                .forEachOrdered(new java.util.function.Consumer<>() {
                    private int rank = 1;

                    @Override
                    public void accept(MutableStats entry) {
                        entry.killsRank = rank++;
                    }
                });
        stats.stream()
                .filter(entry -> entry.deaths > 0)
                .sorted(Comparator.comparingInt((MutableStats entry) -> entry.deaths).reversed())
                .forEachOrdered(new java.util.function.Consumer<>() {
                    private int rank = 1;

                    @Override
                    public void accept(MutableStats entry) {
                        entry.deathsRank = rank++;
                    }
                });

        return stats.stream()
                .map(entry -> new PlayerStatEntry(entry.uuid, entry.name, entry.kills, entry.deaths, entry.killsRank, entry.deathsRank, entry.updatedAt))
                .toList();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private static final class MutableStats {
        private final UUID uuid;
        private final String name;
        private final int kills;
        private final int deaths;
        private final long updatedAt;
        private int killsRank;
        private int deathsRank;

        private MutableStats(UUID uuid, String name, int kills, int deaths, long updatedAt) {
            this.uuid = uuid;
            this.name = name;
            this.kills = kills;
            this.deaths = deaths;
            this.updatedAt = updatedAt;
        }
    }
}
