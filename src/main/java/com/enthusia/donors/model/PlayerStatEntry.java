package com.enthusia.donors.model;

import java.util.UUID;

public record PlayerStatEntry(
        UUID uuid,
        String name,
        int kills,
        int deaths,
        int killsRank,
        int deathsRank,
        long updatedAt
) {
}
