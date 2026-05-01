package com.enthusia.donors.service;

import com.enthusia.donors.cache.PlayerStatCache;
import com.enthusia.donors.model.PlayerStatEntry;
import com.enthusia.donors.storage.PlayerStatRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

public final class PlayerStatService implements Listener {
    private final Plugin plugin;
    private final Logger logger;
    private final PlayerStatRepository repository;
    private final PlayerStatCache cache;
    private final ExecutorService ioExecutor;

    public PlayerStatService(Plugin plugin, PlayerStatRepository repository, PlayerStatCache cache) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.repository = repository;
        this.cache = cache;
        this.ioExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("EnthusiaDonors-Stats"));
    }

    public void start() {
        CompletableFuture.runAsync(() -> {
            try {
                repository.init();
                refreshCache();
            } catch (Exception ex) {
                logger.warning("Could not load player stat cache: " + ex.getMessage());
            }
        }, ioExecutor).thenRun(this::importBukkitStats);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void importBukkitStats() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            long now = Instant.now().toEpochMilli();
            List<PlayerStatEntry> imported = new ArrayList<>();
            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                if (offlinePlayer.getUniqueId() == null) {
                    continue;
                }
                int kills = safeStatistic(offlinePlayer, Statistic.PLAYER_KILLS);
                int deaths = safeStatistic(offlinePlayer, Statistic.DEATHS);
                if (kills <= 0 && deaths <= 0) {
                    continue;
                }
                imported.add(new PlayerStatEntry(
                        offlinePlayer.getUniqueId(),
                        safeName(offlinePlayer),
                        kills,
                        deaths,
                        0,
                        0,
                        now
                ));
            }
            CompletableFuture.runAsync(() -> {
                try {
                    repository.upsertStats(imported);
                    refreshCache();
                    logger.info("Imported Bukkit kill/death stats for " + imported.size() + " players.");
                } catch (Exception ex) {
                    logger.warning("Could not import Bukkit kill/death stats: " + ex.getMessage());
                }
            }, ioExecutor);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        CompletableFuture.runAsync(() -> {
            try {
                repository.incrementDeath(victim.getUniqueId(), victim.getName());
                if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
                    repository.incrementKill(killer.getUniqueId(), killer.getName());
                }
                refreshCache();
            } catch (Exception ex) {
                logger.warning("Could not update kill/death stat cache: " + ex.getMessage());
            }
        }, ioExecutor);
    }

    public PlayerStatCache cache() {
        return cache;
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
    }

    private void refreshCache() throws Exception {
        List<PlayerStatEntry> stats = repository.loadRankedStats();
        cache.replace(stats, Instant.now());
    }

    private int safeStatistic(OfflinePlayer player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String safeName(OfflinePlayer player) {
        String name = player.getName();
        return name == null || name.isBlank() ? player.getUniqueId().toString() : name;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;

        private NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
