package com.enthusia.donors.tebex;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.PaymentRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public final class TebexClient {
    private static final URI PAYMENTS_URI = URI.create("https://plugin.tebex.io/payments");

    private final Logger logger;

    public TebexClient(Logger logger) {
        this.logger = logger;
    }

    public CompletableFuture<List<PaymentRecord>> fetchPayments(DonorsConfig config) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        return fetchPage(client, config, 1, new ArrayList<>());
    }

    public CompletableFuture<Set<String>> resolveExcludedPaymentIdHashes(DonorsConfig config) {
        if (config.excludedTransactionIds().isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        List<CompletableFuture<Optional<String>>> futures = config.excludedTransactionIds().stream()
                .map(transactionId -> fetchPaymentIdHash(client, config, transactionId))
                .toList();
        CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(all).thenApply(ignored -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet()));
    }

    private CompletableFuture<Optional<String>> fetchPaymentIdHash(HttpClient client, DonorsConfig config, String transactionId) {
        String encoded = URLEncoder.encode(transactionId, StandardCharsets.UTF_8);
        URI uri = URI.create(PAYMENTS_URI + "/" + encoded);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", "EnthusiaDonors/1.0")
                .header("X-Tebex-Secret", config.tebexApiKey())
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        logger.warning("Could not resolve one configured excluded Tebex transaction; HTTP " + response.statusCode() + ".");
                        return Optional.<String>empty();
                    }
                    JsonObject payment = JsonParser.parseString(response.body()).getAsJsonObject();
                    return stringValue(payment, "id").map(id -> {
                        try {
                            return hashId(id);
                        } catch (Exception ex) {
                            throw new IllegalStateException("Unable to hash resolved Tebex payment ID", ex);
                        }
                    });
                })
                .exceptionally(ex -> {
                    logger.warning("Could not resolve one configured excluded Tebex transaction; keeping refresh alive.");
                    return Optional.empty();
                });
    }

    private CompletableFuture<List<PaymentRecord>> fetchPage(
            HttpClient client,
            DonorsConfig config,
            int page,
            List<PaymentRecord> collected
    ) {
        if (page > config.maxPages()) {
            logger.warning("Tebex refresh stopped at configured max-pages=" + config.maxPages() + ".");
            return CompletableFuture.completedFuture(collected);
        }

        URI uri = URI.create(PAYMENTS_URI + "?paged=1&page=" + URLEncoder.encode(String.valueOf(page), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", "EnthusiaDonors/1.0")
                .header("X-Tebex-Secret", config.tebexApiKey())
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return CompletableFuture.failedFuture(new TebexException("Tebex returned HTTP " + response.statusCode()));
                    }
                    PageResult pageResult = parsePayments(response.body());
                    collected.addAll(pageResult.payments());
                    if (pageResult.hasNextPage() && !pageResult.payments().isEmpty()) {
                        return fetchPage(client, config, page + 1, collected);
                    }
                    return CompletableFuture.completedFuture(collected);
                });
    }

    private PageResult parsePayments(String body) {
        JsonElement root = JsonParser.parseString(body);
        JsonArray data;
        boolean hasNext = false;
        if (root.isJsonArray()) {
            data = root.getAsJsonArray();
        } else {
            JsonObject object = root.getAsJsonObject();
            data = object.has("data") && object.get("data").isJsonArray() ? object.getAsJsonArray("data") : new JsonArray();
            int current = intValue(object, "current_page").orElse(1);
            int last = intValue(object, "last_page").orElse(current);
            hasNext = current < last && !isNullOrBlank(object, "next_page_url");
        }

        List<PaymentRecord> payments = new ArrayList<>();
        Instant importedAt = Instant.now();
        int skipped = 0;
        for (JsonElement element : data) {
            try {
                parsePayment(element.getAsJsonObject(), importedAt).ifPresent(payments::add);
            } catch (Exception ex) {
                skipped++;
            }
        }
        if (skipped > 0) {
            logger.warning("Skipped " + skipped + " Tebex payments with missing or invalid public player data.");
        }
        return new PageResult(payments, hasNext);
    }

    private Optional<PaymentRecord> parsePayment(JsonObject object, Instant importedAt) throws Exception {
        String rawId = stringValue(object, "id").orElse("");
        if (rawId.isBlank()) {
            return Optional.empty();
        }

        JsonObject player = object.has("player") && object.get("player").isJsonObject()
                ? object.getAsJsonObject("player")
                : new JsonObject();
        Optional<UUID> uuid = stringValue(player, "uuid").flatMap(this::parseUuid);
        String name = stringValue(player, "name").orElse("Unknown");
        if (uuid.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal amount = new BigDecimal(stringValue(object, "amount").orElse("0")).max(BigDecimal.ZERO);
        String status = stringValue(object, "status").orElse("Unknown");
        boolean invalidStatus = switch (normalizeStatus(status)) {
            case "complete", "completed", "successful", "success", "paid" -> false;
            case "refund", "refunded", "chargeback", "cancelled", "canceled", "failed" -> true;
            default -> true;
        };

        String currency = "";
        if (object.has("currency") && object.get("currency").isJsonObject()) {
            currency = stringValue(object.getAsJsonObject("currency"), "iso_4217").orElse("");
        }

        Instant createdAt = parseInstant(stringValue(object, "date").orElse(null)).orElse(importedAt);
        return Optional.of(new PaymentRecord(
                hashId(rawId),
                uuid.get(),
                name,
                amount,
                currency,
                status,
                createdAt,
                invalidStatus,
                isManualPayment(object),
                packageIds(object),
                importedAt
        ));
    }

    private List<Integer> packageIds(JsonObject object) {
        if (!object.has("packages") || !object.get("packages").isJsonArray()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray("packages")) {
            if (!element.isJsonObject()) {
                continue;
            }
            intValue(element.getAsJsonObject(), "id").ifPresent(ids::add);
        }
        return List.copyOf(ids);
    }

    private boolean isManualPayment(JsonObject object) {
        if (booleanValue(object, "manual").orElse(false)) {
            return true;
        }
        return stringValue(object, "type").map(type -> type.toLowerCase(Locale.ROOT).contains("manual")).orElse(false)
                || stringValue(object, "payment_type").map(type -> type.toLowerCase(Locale.ROOT).contains("manual")).orElse(false)
                || stringValue(object, "source").map(type -> type.toLowerCase(Locale.ROOT).contains("manual")).orElse(false);
    }

    private Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (Exception ignored) {
            try {
                return Optional.of(OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant());
            } catch (Exception ignoredAgain) {
                return Optional.empty();
            }
        }
    }

    private Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim();
        if (!normalized.contains("-") && normalized.length() == 32) {
            normalized = normalized.substring(0, 8) + "-" + normalized.substring(8, 12) + "-"
                    + normalized.substring(12, 16) + "-" + normalized.substring(16, 20) + "-"
                    + normalized.substring(20);
        }
        try {
            return Optional.of(UUID.fromString(normalized));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String hashId(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Optional<String> stringValue(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return Optional.empty();
        }
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive()) {
            return Optional.of(value.getAsString());
        }
        return Optional.empty();
    }

    private Optional<Integer> intValue(JsonObject object, String key) {
        return stringValue(object, key).map(Integer::parseInt);
    }

    private Optional<Boolean> booleanValue(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
            return Optional.empty();
        }
        return Optional.of(object.get(key).getAsBoolean());
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
    }

    private boolean isNullOrBlank(JsonObject object, String key) {
        return stringValue(object, key).map(String::isBlank).orElse(true);
    }

    private record PageResult(List<PaymentRecord> payments, boolean hasNextPage) {
    }

    public static final class TebexException extends RuntimeException {
        public TebexException(String message) {
            super(message);
        }
    }
}
