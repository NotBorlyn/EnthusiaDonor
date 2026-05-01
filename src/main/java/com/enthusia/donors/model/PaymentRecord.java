package com.enthusia.donors.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentRecord(
        String paymentIdHash,
        UUID playerUuid,
        String playerName,
        BigDecimal amount,
        String currency,
        String status,
        Instant createdAt,
        boolean refundedOrChargeback,
        boolean manualPayment,
        List<Integer> packageIds,
        Instant importedAt
) {
}
