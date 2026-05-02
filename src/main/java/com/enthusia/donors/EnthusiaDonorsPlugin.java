package com.enthusia.donors;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.cache.PlayerStatCache;
import com.enthusia.donors.command.DonorCommand;
import com.enthusia.donors.config.ConfigManager;
import com.enthusia.donors.export.JsonExportService;
import com.enthusia.donors.export.R2UploadService;
import com.enthusia.donors.placeholder.PlaceholderHook;
import com.enthusia.donors.service.LeaderboardService;
import com.enthusia.donors.service.PlayerStatService;
import com.enthusia.donors.storage.DonorRepository;
import com.enthusia.donors.storage.PlayerStatRepository;
import com.enthusia.donors.tebex.TebexClient;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class EnthusiaDonorsPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private DonorCache cache;
    private PlayerStatCache playerStatCache;
    private LeaderboardService leaderboardService;
    private PlayerStatService playerStatService;
    private PlaceholderHook placeholderHook;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();
        cache = new DonorCache();
        playerStatCache = new PlayerStatCache();

        DonorRepository repository = new DonorRepository(getDataFolder().toPath(), getLogger());
        leaderboardService = new LeaderboardService(
                this,
                configManager,
                repository,
                new TebexClient(getLogger()),
                cache,
                new JsonExportService(getLogger()),
                new R2UploadService(getLogger())
        );
        leaderboardService.start();

        playerStatService = new PlayerStatService(
                this,
                new PlayerStatRepository(getDataFolder().toPath()),
                playerStatCache
        );
        playerStatService.start();

        PluginCommand command = getCommand("enthusiadonors");
        if (command != null) {
            DonorCommand executor = new DonorCommand(configManager, cache, leaderboardService, this::reloadPlugin);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderHook = new PlaceholderHook(this, configManager, cache, playerStatCache);
            placeholderHook.register();
            getLogger().info("Registered PlaceholderAPI expansion: enthusiadonors.");
        } else {
            getLogger().info("PlaceholderAPI not found; placeholders are disabled, plugin remains active.");
        }
    }

    @Override
    public void onDisable() {
        if (placeholderHook != null) {
            placeholderHook.unregister();
        }
        if (leaderboardService != null) {
            leaderboardService.shutdown();
        }
        if (playerStatService != null) {
            playerStatService.shutdown();
        }
    }

    private void reloadPlugin() {
        configManager.load();
        leaderboardService.schedule();
        leaderboardService.refresh(false);
    }
}
