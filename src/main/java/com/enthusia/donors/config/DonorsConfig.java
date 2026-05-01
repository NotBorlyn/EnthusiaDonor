package com.enthusia.donors.config;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Set;

public record DonorsConfig(
        String tebexApiKey,
        int refreshIntervalMinutes,
        int timeoutSeconds,
        int maxRetries,
        int maxPages,
        String countingSource,
        boolean countZeroDollarPayments,
        boolean countManualPayments,
        boolean countRefundedPayments,
        boolean countChargebackPayments,
        Set<Integer> includedPackageIds,
        Set<Integer> excludedPackageIds,
        Set<String> excludedTransactionIds,
        Set<String> excludedPaymentIdHashes,
        ZoneId timezone,
        String currencySymbol,
        boolean showCents,
        int topSize,
        String emptyName,
        String emptyAmount,
        String emptyRank,
        boolean useSqlite,
        boolean saveJsonExport,
        Path jsonExportPath,
        boolean exposeRawPaymentData,
        boolean hashPaymentIds,
        boolean fakeDataEnabled,
        int fakePlayerCount
) {
}
