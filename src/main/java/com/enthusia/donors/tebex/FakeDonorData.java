package com.enthusia.donors.tebex;

import com.enthusia.donors.model.PaymentRecord;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FakeDonorData {
    private FakeDonorData() {
    }

    public static List<PaymentRecord> payments(int count) {
        List<PaymentRecord> result = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 1; i <= count; i++) {
            UUID uuid = UUID.nameUUIDFromBytes(("enthusia-test-player-" + i).getBytes(StandardCharsets.UTF_8));
            result.add(new PaymentRecord(
                    "fake-" + i,
                    uuid,
                    "TestDonor" + i,
                    BigDecimal.valueOf((count - i + 1) * 7.5),
                    "USD",
                    "Complete",
                    now.minus(i, ChronoUnit.DAYS),
                    false,
                    false,
                    List.of(1000 + i),
                    now
            ));
        }
        return result;
    }
}
