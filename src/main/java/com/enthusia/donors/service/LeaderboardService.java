package com.enthusia.donors.service;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.config.ConfigManager;
import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.export.JsonExportService;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.model.PaymentRecord;
import com.enthusia.donors.model.RefreshState;
import com.enthusia.donors.storage.DonorRepository;
import com.enthusia.donors.tebex.FakeDonorData;
import com.enthusia.donors.tebex.TebexClient;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class LeaderboardService {
    private final Plugin plugin;
    private final Logger logger;
    private final ConfigManager configManager;
    private final DonorRepository repository;
    private final TebexClient tebexClient;
    private final DonorCache cache;
    private final JsonExportService jsonExportService;
    private final ExecutorService ioExecutor;
    private BukkitTask refreshTask;

    public LeaderboardService(
            Plugin plugin,
            ConfigManager configManager,
            DonorRepository repository,
            TebexClient tebexClient,
            DonorCache cache,
            JsonExportService jsonExportService
    ) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configManager = configManager;
        this.repository = repository;
        this.tebexClient = tebexClient;
        this.cache = cache;
        this.jsonExportService = jsonExportService;
        this.ioExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("EnthusiaDonors-IO"));
    }

    public void start() {
        CompletableFuture.runAsync(() -> {
            try {
                repository.init();
                List<DonorEntry> donors = repository.loadTotals();
                if (!donors.isEmpty()) {
                    Instant updated = Instant.ofEpochMilli(donors.get(0).updatedAt());
                    cache.replace(donors, updated, RefreshState.CACHE_ONLY);
                    logger.info("Loaded " + donors.size() + " cached donor totals.");
                } else {
                    cache.markCacheOnly();
                    logger.info("No cached donor totals found yet.");
                }
            } catch (Exception ex) {
                cache.markFailure("Local cache load failed");
                logger.warning("Could not load local donor cache: " + ex.getMessage());
            }
        }, ioExecutor).thenRun(() -> refresh(false));
        schedule();
    }

    public void schedule() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        long ticks = Math.max(1, configManager.get().refreshIntervalMinutes()) * 60L * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> refresh(false), ticks, ticks);
    }

    public CompletableFuture<Void> refresh(boolean forced) {
        DonorsConfig config = configManager.get();
        cache.markAttempt();

        CompletableFuture<RefreshData> source;
        RefreshState successState = RefreshState.OK;
        if (config.fakeDataEnabled()) {
            source = CompletableFuture.supplyAsync(() -> new RefreshData(FakeDonorData.payments(config.fakePlayerCount()), Set.of()), ioExecutor);
            successState = RefreshState.TEST_DATA;
        } else if (config.tebexApiKey() == null || config.tebexApiKey().isBlank()) {
            cache.markNotConfigured();
            if (forced) {
                logger.warning("Tebex API key is not configured. Enable testing.fake-data-enabled for placeholder testing.");
            }
            return CompletableFuture.completedFuture(null);
        } else {
            source = fetchWithRetries(config, 0).thenCombine(
                    tebexClient.resolveExcludedPaymentIdHashes(config),
                    RefreshData::new
            );
        }

        RefreshState finalSuccessState = successState;
        return source.thenCompose(refreshData -> CompletableFuture.runAsync(() -> {
            try {
                if (refreshData.payments().isEmpty() && !config.fakeDataEnabled()) {
                    throw new IllegalStateException("Tebex returned no usable payments; keeping existing cache.");
                }
                int written = repository.upsertPayments(refreshData.payments());
                List<DonorEntry> totals = repository.rebuildTotals(config, refreshData.extraExcludedPaymentIdHashes());
                Instant updated = Instant.now();
                cache.replace(totals, updated, finalSuccessState);
                jsonExportService.export(cache.snapshot(), cache, config);
                logger.info("Donor refresh complete. Imported/updated " + written + " payments; " + totals.size() + " donors cached.");
            } catch (Exception ex) {
                cache.markFailure("Cache update failed");
                logger.warning("Donor cache update failed; keeping last valid in-memory data: " + ex.getMessage());
            }
        }, ioExecutor)).exceptionally(ex -> {
            String message = ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage();
            cache.markFailure("Tebex refresh failed");
            logger.warning("Tebex donor refresh failed; keeping last valid cache: " + safe(message));
            return null;
        });
    }

    private CompletableFuture<List<PaymentRecord>> fetchWithRetries(DonorsConfig config, int attempt) {
        return tebexClient.fetchPayments(config).handle((payments, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(payments);
            }
            if (attempt >= config.maxRetries()) {
                return CompletableFuture.<List<PaymentRecord>>failedFuture(error);
            }
            long backoffMillis = Math.min(30_000L, (long) Math.pow(2, attempt) * 1000L);
            logger.warning("Tebex refresh attempt " + (attempt + 1) + " failed; retrying in " + backoffMillis + "ms.");
            return sleep(backoffMillis).thenCompose(v -> fetchWithRetries(config, attempt + 1));
        }).thenCompose(future -> future);
    }

    private CompletableFuture<Void> sleep(long millis) {
        return CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(millis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, ioExecutor);
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        ioExecutor.shutdownNow();
    }

    private String safe(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String apiKey = configManager.get().tebexApiKey();
        return apiKey == null || apiKey.isBlank() ? message : message.replace(apiKey, "[redacted]");
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

    private record RefreshData(List<PaymentRecord> payments, Set<String> extraExcludedPaymentIdHashes) {
    }
}
