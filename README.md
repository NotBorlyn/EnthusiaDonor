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

## Website Donor Leaderboard Export

The plugin can upload a public-safe all-time donor leaderboard JSON file to Cloudflare R2 for the website to read through a Pages Function. The browser should call the website endpoint, not Tebex or R2 credentials directly.

Recommended R2 object locations:

```text
r2://donator-leaderboard/leaderboards/donators-all-time.json
r2://donator-leaderboard/leaderboards/index.json
```

Enable uploads in `config.yml`:

```yaml
r2:
  enabled: true
  account-id: "<cloudflare-account-id>"
  endpoint: ""
  access-key-id: "<r2-access-key-id>"
  secret-access-key: "<r2-secret-access-key>"
  bucket: "donator-leaderboard"
  object-path: "leaderboards/donators-all-time.json"
  index-object-path: "leaderboards/index.json"
```

`endpoint` can be left blank when `account-id` is set; the plugin will use `https://<account-id>.r2.cloudflarestorage.com`. The uploaded leaderboard shape is:

```json
{
  "board": "donators-all-time",
  "label": "Top Donators",
  "statLabel": "Support",
  "generatedAt": "2026-05-02T00:00:00Z",
  "source": "EnthusiaDonators",
  "order": "desc",
  "players": [
    {
      "rank": 1,
      "uuid": "player-uuid-here",
      "username": "PlayerName",
      "displayName": "PlayerName",
      "value": 1000,
      "formattedValue": "$1000.00",
      "subtext": "All-time support"
    }
  ]
}
```

Only public display data is uploaded: rank, UUID, username/display name, numeric value, formatted value, and subtext.

## Tebex API

Configure `tebex.api-key` with your Tebex server secret. The plugin uses `GET https://plugin.tebex.io/payments?paged=1&page=N` with the `X-Tebex-Secret` header.

## Commands

- `/enthusiadonors reload`
- `/enthusiadonors refresh`
- `/enthusiadonors status`
- `/enthusiadonors top alltime`
- `/enthusiadonors top monthly`
- `/enthusiadonors debug <player>`

## Kill And Death Placeholders

EnthusiaDonors imports existing Bukkit statistics for known players on startup, stores them in SQLite, updates them from live player death events, and exposes cached leaderboard placeholders.

Top kills:

```text
%enthusiadonors_kills_top_1_name%
%enthusiadonors_kills_top_1_uuid%
%enthusiadonors_kills_top_1_kills%
%enthusiadonors_kills_top_1_value%
%enthusiadonors_kills_top_1_rank%
```

Top deaths:

```text
%enthusiadonors_deaths_top_1_name%
%enthusiadonors_deaths_top_1_uuid%
%enthusiadonors_deaths_top_1_deaths%
%enthusiadonors_deaths_top_1_value%
%enthusiadonors_deaths_top_1_rank%
```

Ranks 1-10 use the same pattern. General stat placeholders:

```text
%enthusiadonors_kills_count%
%enthusiadonors_deaths_count%
%enthusiadonors_stats_last_updated%
```

## Testing

Set `testing.fake-data-enabled: true`, or run `/enthusiadonors refresh` with no Tebex key configured, to exercise placeholders and commands with deterministic fake donor data.
