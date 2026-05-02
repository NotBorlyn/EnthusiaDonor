package com.enthusia.donors.export;

import com.enthusia.donors.cache.DonorCache;
import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.DonorEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

public final class JsonExportService {
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public JsonExportService(Logger logger) {
        this.logger = logger;
    }

    public void export(DonorCache.Snapshot snapshot, DonorCache cache, DonorsConfig config) {
        if (!config.saveJsonExport()) {
            return;
        }
        try {
            Files.createDirectories(config.jsonExportPath().getParent());
            JsonObject root = new JsonObject();
            root.addProperty("lastUpdated", snapshot.updatedAt() == null ? "" : snapshot.updatedAt().toString());
            root.add("alltime", donors(snapshot.alltime(), config.topSize(), true, cache, config));
            root.add("monthly", donors(snapshot.monthly(), config.topSize(), false, cache, config));
            Files.writeString(config.jsonExportPath(), gson.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            logger.warning("Could not write safe donor JSON export: " + ex.getMessage());
        }
    }

    public String donatorsAllTime(DonorCache.Snapshot snapshot, DonorCache cache, DonorsConfig config) {
        JsonObject root = new JsonObject();
        Instant generatedAt = snapshot.updatedAt() == null ? Instant.now() : snapshot.updatedAt();
        root.addProperty("board", "donators-all-time");
        root.addProperty("label", "Top Donators");
        root.addProperty("statLabel", "Support");
        root.addProperty("generatedAt", generatedAt.toString());
        root.addProperty("source", "EnthusiaDonators");
        root.addProperty("order", "desc");
        root.add("players", websiteDonors(snapshot.alltime(), config.topSize(), cache, config));
        return gson.toJson(root);
    }

    public String index(DonorCache.Snapshot snapshot, DonorsConfig config) {
        Instant generatedAt = snapshot.updatedAt() == null ? Instant.now() : snapshot.updatedAt();
        JsonObject board = new JsonObject();
        board.addProperty("board", "donators-all-time");
        board.addProperty("label", "Top Donators");
        board.addProperty("statLabel", "Support");
        board.addProperty("generatedAt", generatedAt.toString());
        board.addProperty("path", config.r2ObjectPath());

        JsonArray boards = new JsonArray();
        boards.add(board);

        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", generatedAt.toString());
        root.add("boards", boards);
        return gson.toJson(root);
    }

    private JsonArray donors(List<DonorEntry> donors, int topSize, boolean alltime, DonorCache cache, DonorsConfig config) {
        JsonArray array = new JsonArray();
        donors.stream().limit(topSize).forEach(d -> {
            JsonObject object = new JsonObject();
            object.addProperty("rank", alltime ? d.alltimeRank() : d.monthlyRank());
            object.addProperty("name", d.name());
            object.addProperty("uuid", d.uuid().toString());
            object.addProperty("amount", cache.formatAmount(alltime ? d.alltimeTotal() : d.monthlyTotal(), config));
            object.addProperty("amountRaw", alltime ? d.alltimeTotal() : d.monthlyTotal());
            array.add(object);
        });
        return array;
    }

    private JsonArray websiteDonors(List<DonorEntry> donors, int topSize, DonorCache cache, DonorsConfig config) {
        JsonArray array = new JsonArray();
        donors.stream().limit(topSize).forEach(d -> {
            JsonObject object = new JsonObject();
            object.addProperty("rank", d.alltimeRank());
            object.addProperty("uuid", d.uuid().toString());
            object.addProperty("username", d.name());
            object.addProperty("displayName", d.name());
            object.addProperty("value", d.alltimeTotal());
            object.addProperty("formattedValue", cache.formatAmount(d.alltimeTotal(), config));
            object.addProperty("subtext", "All-time support");
            array.add(object);
        });
        return array;
    }
}
