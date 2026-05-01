package com.enthusia.donors.storage;

import com.enthusia.donors.config.DonorsConfig;
import com.enthusia.donors.model.DonorEntry;
import com.enthusia.donors.model.PaymentRecord;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DonorRepository {
    private final Path databasePath;

    public DonorRepository(Path dataFolder, java.util.logging.Logger logger) {
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
                    CREATE TABLE IF NOT EXISTS payments (
                      payment_id_hash TEXT PRIMARY KEY,
                      player_uuid TEXT NOT NULL,
                      player_name TEXT NOT NULL,
                      amount TEXT NOT NULL,
                      currency TEXT,
                      status TEXT,
                      created_at INTEGER NOT NULL,
                      refunded_or_chargeback INTEGER NOT NULL,
                      manual_payment INTEGER NOT NULL DEFAULT 0,
                      package_ids TEXT NOT NULL DEFAULT '',
                      imported_at INTEGER NOT NULL
                    )
                    """);
            addColumnIfMissing(c, "payments", "manual_payment", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(c, "payments", "package_ids", "TEXT NOT NULL DEFAULT ''");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS donor_totals (
                      player_uuid TEXT PRIMARY KEY,
                      player_name TEXT NOT NULL,
                      alltime_total TEXT NOT NULL,
                      monthly_total TEXT NOT NULL,
                      alltime_rank INTEGER NOT NULL,
                      monthly_rank INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    public int upsertPayments(List<PaymentRecord> payments) throws SQLException {
        if (payments.isEmpty()) {
            return 0;
        }
        int written = 0;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO payments
                    (payment_id_hash, player_uuid, player_name, amount, currency, status, created_at, refunded_or_chargeback, manual_payment, package_ids, imported_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(payment_id_hash) DO UPDATE SET
                      player_uuid=excluded.player_uuid,
                      player_name=excluded.player_name,
                      amount=excluded.amount,
                      currency=excluded.currency,
                      status=excluded.status,
                      created_at=excluded.created_at,
                      refunded_or_chargeback=excluded.refunded_or_chargeback,
                      manual_payment=excluded.manual_payment,
                      package_ids=excluded.package_ids,
                      imported_at=excluded.imported_at
                    """)) {
                for (PaymentRecord p : payments) {
                    ps.setString(1, p.paymentIdHash());
                    ps.setString(2, p.playerUuid().toString());
                    ps.setString(3, p.playerName());
                    ps.setString(4, p.amount().toPlainString());
                    ps.setString(5, p.currency());
                    ps.setString(6, p.status());
                    ps.setLong(7, p.createdAt().toEpochMilli());
                    ps.setInt(8, p.refundedOrChargeback() ? 1 : 0);
                    ps.setInt(9, p.manualPayment() ? 1 : 0);
                    ps.setString(10, encodePackageIds(p.packageIds()));
                    ps.setLong(11, p.importedAt().toEpochMilli());
                    ps.addBatch();
                }
                for (int result : ps.executeBatch()) {
                    if (result >= 0 || result == Statement.SUCCESS_NO_INFO) {
                        written++;
                    }
                }
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
            c.commit();
        }
        return written;
    }

    public List<DonorEntry> rebuildTotals(DonorsConfig config, Set<String> extraExcludedPaymentIdHashes) throws SQLException {
        ZoneId zone = config.timezone();
        LocalDate monthStart = LocalDate.now(zone).withDayOfMonth(1);
        long monthStartMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli();
        Instant updated = Instant.now();

        Map<UUID, MutableDonor> donors = new HashMap<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT payment_id_hash, player_uuid, player_name, amount, status, created_at, refunded_or_chargeback, manual_payment, package_ids
                FROM payments
                """)) {
            while (rs.next()) {
                BigDecimal amount = new BigDecimal(rs.getString("amount"));
                Set<Integer> packageIds = decodePackageIds(rs.getString("package_ids"));
                if (!isCountable(
                        amount,
                        rs.getString("status"),
                        rs.getInt("refunded_or_chargeback") == 1,
                        rs.getInt("manual_payment") == 1,
                        packageIds,
                        rs.getString("payment_id_hash"),
                        extraExcludedPaymentIdHashes,
                        config
                )) {
                    continue;
                }

                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                String playerName = safePlayerName(rs);
                MutableDonor donor = donors.computeIfAbsent(uuid, ignored -> new MutableDonor(uuid, playerName, BigDecimal.ZERO, BigDecimal.ZERO));
                donor.alltimeTotal = donor.alltimeTotal.add(amount);
                if (rs.getLong("created_at") >= monthStartMillis) {
                    donor.monthlyTotal = donor.monthlyTotal.add(amount);
                }
            }
        }

        List<MutableDonor> donorList = new ArrayList<>(donors.values());
        donorList.sort((a, b) -> b.alltimeTotal.compareTo(a.alltimeTotal));
        for (int i = 0; i < donorList.size(); i++) {
            donorList.get(i).alltimeRank = i + 1;
        }

        List<MutableDonor> monthlyRanked = donorList.stream()
                .filter(d -> d.monthlyTotal.compareTo(BigDecimal.ZERO) > 0)
                .sorted((a, b) -> b.monthlyTotal.compareTo(a.monthlyTotal))
                .toList();
        for (int i = 0; i < monthlyRanked.size(); i++) {
            monthlyRanked.get(i).monthlyRank = i + 1;
        }

        List<DonorEntry> result = donorList.stream()
                .map(d -> new DonorEntry(d.uuid, d.name, d.alltimeTotal, d.monthlyTotal, d.alltimeRank, d.monthlyRank, updated.toEpochMilli()))
                .toList();
        replaceTotals(result);
        return result;
    }

    private boolean isCountable(
            BigDecimal amount,
            String status,
            boolean refundedOrChargeback,
            boolean manualPayment,
            Set<Integer> packageIds,
            String paymentIdHash,
            Set<String> extraExcludedPaymentIdHashes,
            DonorsConfig config
    ) {
        String normalizedPaymentHash = paymentIdHash == null ? "" : paymentIdHash.toLowerCase(Locale.ROOT);
        if (config.excludedPaymentIdHashes().contains(normalizedPaymentHash) || extraExcludedPaymentIdHashes.contains(normalizedPaymentHash)) {
            return false;
        }
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
        boolean successStatus = switch (normalized) {
            case "complete", "completed", "successful", "success", "paid" -> true;
            default -> false;
        };
        boolean refundStatus = normalized.equals("refund") || normalized.equals("refunded");
        boolean chargebackStatus = normalized.equals("chargeback");
        boolean deniedStatus = switch (normalized) {
            case "canceled", "cancelled", "failed", "pending", "denied", "voided", "void" -> true;
            default -> false;
        };

        if (deniedStatus) {
            return false;
        }
        if (!successStatus && !(refundStatus && config.countRefundedPayments()) && !(chargebackStatus && config.countChargebackPayments())) {
            return false;
        }
        if (refundedOrChargeback && !config.countRefundedPayments() && !config.countChargebackPayments()) {
            return false;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0 && !config.countZeroDollarPayments()) {
            return false;
        }
        if (manualPayment && !config.countManualPayments()) {
            return false;
        }
        if (!config.excludedPackageIds().isEmpty() && intersects(packageIds, config.excludedPackageIds())) {
            return false;
        }
        return config.includedPackageIds().isEmpty() || intersects(packageIds, config.includedPackageIds());
    }

    public List<DonorEntry> loadTotals() throws SQLException {
        List<DonorEntry> donors = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT player_uuid, player_name, alltime_total, monthly_total, alltime_rank, monthly_rank, updated_at
                FROM donor_totals
                ORDER BY alltime_rank ASC
                """)) {
            while (rs.next()) {
                donors.add(new DonorEntry(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        new BigDecimal(rs.getString("alltime_total")),
                        new BigDecimal(rs.getString("monthly_total")),
                        rs.getInt("alltime_rank"),
                        rs.getInt("monthly_rank"),
                        rs.getLong("updated_at")
                ));
            }
        }
        return donors;
    }

    private void replaceTotals(List<DonorEntry> donors) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM donor_totals");
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO donor_totals
                    (player_uuid, player_name, alltime_total, monthly_total, alltime_rank, monthly_rank, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (DonorEntry d : donors) {
                    ps.setString(1, d.uuid().toString());
                    ps.setString(2, d.name());
                    ps.setString(3, d.alltimeTotal().toPlainString());
                    ps.setString(4, d.monthlyTotal().toPlainString());
                    ps.setInt(5, d.alltimeRank());
                    ps.setInt(6, d.monthlyRank());
                    ps.setLong(7, d.updatedAt());
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

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private void addColumnIfMissing(Connection c, String table, String column, String definition) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean intersects(Set<Integer> a, Set<Integer> b) {
        for (Integer value : a) {
            if (b.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String encodePackageIds(List<Integer> packageIds) {
        return packageIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    private Set<Integer> decodePackageIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<Integer> ids = new HashSet<>();
        for (String part : value.split(",")) {
            try {
                ids.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return Set.copyOf(ids);
    }

    private String safePlayerName(ResultSet rs) throws SQLException {
        String name = rs.getString("player_name");
        return name == null || name.isBlank() ? rs.getString("player_uuid") : name;
    }

    private static final class MutableDonor {
        private final UUID uuid;
        private final String name;
        private BigDecimal alltimeTotal;
        private BigDecimal monthlyTotal;
        private int alltimeRank;
        private int monthlyRank;

        private MutableDonor(UUID uuid, String name, BigDecimal alltimeTotal, BigDecimal monthlyTotal) {
            this.uuid = uuid;
            this.name = name;
            this.alltimeTotal = alltimeTotal;
            this.monthlyTotal = monthlyTotal;
        }
    }
}
