# EnthusiaDonors

Standalone donor leaderboard plugin for Paper/Purpur/Leaf servers using Tebex's server-side Plugin API.

It does not depend on the old BuyCraftAPI PlaceholderAPI expansion or Tebex plugin internals. Tebex data is fetched asynchronously, written to SQLite, reduced into safe donor totals, cached in memory, and exposed through PlaceholderAPI placeholders.

## Source Of Truth

Donor totals are calculated only from Tebex payment records. EnthusiaDonors does not read LuckPerms ranks, permissions, Vault groups, rank names, online player permissions, or assigned donor packages/ranks when calculating money spent.

By default, only completed/successful/paid Tebex payments with an amount above zero are counted. Refunds, chargebacks, canceled, failed, pending, denied, and voided payments are excluded unless the relevant counting option explicitly allows them.

Package filtering is controlled by:

```yaml
counting:
  source: "tebex_payments_only"
  count-zero-dollar-payments: false
  count-manual-payments: false
  count-refunded-payments: false
  count-chargeback-payments: false
  included-package-ids: []
  excluded-package-ids: []
  excluded-transaction-ids: []
```

If `included-package-ids` is empty, all valid Tebex purchases are eligible. If it has IDs, only matching package IDs are eligible. `excluded-package-ids` always wins.

Use `excluded-transaction-ids` for private manual exclusions such as test transactions. Tebex's list endpoint returns numeric payment IDs, while dashboard transaction IDs often look like `tbx-...`; during refresh the plugin resolves each configured transaction through `/payments/{transaction}`, hashes the returned internal payment ID, and excludes it from totals. These transaction IDs are never exported.

## Build

```powershell
mvn clean package
```

The plugin jar is produced at `target/EnthusiaDonors-1.0.0.jar`.

## Privacy

Public placeholders and JSON exports only include player UUID, player name, all-time amount, monthly amount, ranks, and last update time. Emails, transaction IDs, notes, gateway data, IPs, and raw payment objects are never exported.

## Tebex API

Configure `tebex.api-key` with your Tebex server secret. The plugin uses `GET https://plugin.tebex.io/payments?paged=1&page=N` with the `X-Tebex-Secret` header.

## Commands

- `/enthusiadonors reload`
- `/enthusiadonors refresh`
- `/enthusiadonors status`
- `/enthusiadonors top alltime`
- `/enthusiadonors top monthly`
- `/enthusiadonors debug <player>`

## Testing

Set `testing.fake-data-enabled: true`, or run `/enthusiadonors refresh` with no Tebex key configured, to exercise placeholders and commands with deterministic fake donor data.
