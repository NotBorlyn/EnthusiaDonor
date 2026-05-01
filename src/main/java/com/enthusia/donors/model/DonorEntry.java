package com.enthusia.donors.model;

import java.math.BigDecimal;
import java.util.UUID;

public record DonorEntry(
        UUID uuid,
        String name,
        BigDecimal alltimeTotal,
        BigDecimal monthlyTotal,
        int alltimeRank,
        int monthlyRank,
        long updatedAt
) {
}
