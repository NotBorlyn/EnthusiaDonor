package com.enthusia.donors.export;

import com.enthusia.donors.config.DonorsConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.logging.Logger;

public final class R2UploadService {
    private static final String REGION = "auto";
    private static final String SERVICE = "s3";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Logger logger;
    private final HttpClient httpClient;
    private final Clock clock;

    public R2UploadService(Logger logger) {
        this(logger, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    R2UploadService(Logger logger, HttpClient httpClient, Clock clock) {
        this.logger = logger;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public void upload(DonorsConfig config, String leaderboardJson, String indexJson) {
        if (!config.r2UploadEnabled()) {
            return;
        }
        if (!configured(config)) {
            logger.warning("R2 upload skipped because r2 credentials are incomplete.");
            return;
        }

        try {
            put(config, config.r2ObjectPath(), leaderboardJson);
            if (config.r2IndexObjectPath() != null && !config.r2IndexObjectPath().isBlank()) {
                put(config, config.r2IndexObjectPath(), indexJson);
            }
            logger.info("Uploaded donor leaderboard JSON to R2 bucket " + config.r2Bucket() + ".");
        } catch (Exception ex) {
            logger.warning("Could not upload donor leaderboard JSON to R2: " + safe(ex.getMessage(), config));
        }
    }

    private void put(DonorsConfig config, String objectPath, String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String payloadHash = sha256Hex(payload);
        URI endpoint = endpoint(config, objectPath);
        Instant now = clock.instant();
        String amzDate = DATE_TIME.format(now);
        String date = DATE.format(now);
        String host = endpoint.getHost();

        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = "PUT\n"
                + endpoint.getRawPath() + "\n\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String credentialScope = date + "/" + REGION + "/" + SERVICE + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(config.r2SecretAccessKey(), date), stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + config.r2AccessKeyId() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                .header("Content-Type", CONTENT_TYPE)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("PUT " + objectPath + " returned HTTP " + response.statusCode());
        }
    }

    private URI endpoint(DonorsConfig config, String objectPath) {
        String base = config.r2Endpoint();
        if (base == null || base.isBlank()) {
            base = "https://" + config.r2AccountId() + ".r2.cloudflarestorage.com";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/" + encodeSegment(config.r2Bucket()) + "/" + encodePath(objectPath));
    }

    private String encodePath(String value) {
        String[] segments = value.split("/");
        StringBuilder encoded = new StringBuilder();
        for (String segment : segments) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(encodeSegment(segment));
        }
        return encoded.toString();
    }

    private String encodeSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private byte[] signingKey(String secretAccessKey, String date) throws Exception {
        byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmac(dateKey, REGION);
        byte[] serviceKey = hmac(regionKey, SERVICE);
        return hmac(serviceKey, "aws4_request");
    }

    private byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Hex(byte[] value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value));
    }

    private boolean configured(DonorsConfig config) {
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

    private String safe(String message, DonorsConfig config) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        String safeMessage = message;
        if (!blank(config.r2AccessKeyId())) {
            safeMessage = safeMessage.replace(config.r2AccessKeyId(), "[redacted]");
        }
        if (!blank(config.r2SecretAccessKey())) {
            safeMessage = safeMessage.replace(config.r2SecretAccessKey(), "[redacted]");
        }
        if (!blank(config.tebexApiKey())) {
            safeMessage = safeMessage.replace(config.tebexApiKey(), "[redacted]");
        }
        return safeMessage;
    }
}
