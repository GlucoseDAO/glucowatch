# Nightscout support

Checked on 2026-09-25 against Nightscout (cgm-remote-monitor) 15.0.3, API v3 3.0.3-alpha, and
its source on `master`. The client is `core/src/main/kotlin/glucowatch/core/NightscoutClient.kt`.

Nightscout is free software (AGPL) that the user runs, so reading from it adds no proprietary
network service. The `NonFreeNet` anti-feature remains because of Dexcom Share.

## What the watch reads

| Data | v1 request | v3 request | Used for |
|---|---|---|---|
| Glucose | `api/v1/entries/sgv.json?find[date][$gte]=<ms>&count=` | `api/v3/entries?type$eq=sgv&date$gte=<ms>&sort$desc=date&limit=` | value, trend, chart |
| Treatments | `api/v1/treatments.json?find[created_at][$gte]=<ISO>&count=` | `api/v3/treatments?created_at$gte=<ISO>&sort$desc=created_at` | basal, bolus and carb markers and latest events |
| Device status | `api/v1/devicestatus.json?find[created_at][$gte]=<ISO>&count=10` | `api/v3/devicestatus?created_at$gte=<ISO>&sort$desc=created_at&limit=10` | IOB, COB, loop forecast |

Everything is read-only. v3 requests pass `fields=` so the server sends only what is used.

The query keys are URL-encoded (`find%5Bdate%5D%5B%24gte%5D`), because `java.net.URI` rejects raw
brackets. Nightscout decodes them. v3 caps `limit` at 1000 (`API3_MAX_LIMIT`), so a first v3 fetch
of 1-minute readings covers about 16 hours; later fetches fill the rest of the day.

The v3 filters use `created_at` for treatments and device status. Documents uploaded through v1
often have no `date`, and v3 writes fill `created_at` by default (`API3_CREATED_AT_FALLBACK_ENABLED`).

## Authentication

| Credential | API v1 | API v3 |
|---|---|---|
| none | works when the site's `AUTH_DEFAULT_ROLES` includes `readable` (the default) | refused, 401 |
| access token (`name-0123456789abcdef`) | sent as `?token=`; the server swaps it for a JWT | exchanged at `api/v2/authorization/request/<token>` for a JWT, sent as `Authorization: Bearer` |
| API secret | SHA-1 hex in the `api-secret` header | not accepted: v3 only takes a JWT |

The watch tells a token from a secret by its shape (up to 10 letters, a dash, 16 hex digits). The
JWT is cached per server and token and renewed once when the server answers 401. A token with the
`readable` role is enough and safer than the API secret, which grants full write access.

The address may carry the credential, as users often paste it: `https://site/?token=…` (share
links) or `https://SECRET@site/api/v1/` (xDrip+ uploader URLs). A pasted `/api/…` path is dropped.

Release builds refuse plain `http://` addresses, because Android blocks cleartext traffic and the
token would travel unencrypted. Debug builds allow `http://10.0.2.2` and `http://localhost` for a
Nightscout on the development machine.

## How uploaders write the data

| Uploader | Treatments | Device status |
|---|---|---|
| iAPS, Trio | `eventType` `Bolus`, `SMB`, `Carb Correction` | `openaps.enacted` with `predBGs`, `openaps.iob`; two documents per loop cycle, only one carries `predBGs` |
| AAPS | `Correction Bolus` / `Meal Bolus` with `isSMB`, `type: SMB` or `PRIMING` | `openaps.suggested` / `enacted` and `openaps.iob` in one document |
| Loop | `Correction Bolus` with `automatic: true`, `Carb Correction` | `loop.iob`, `loop.cob`, `loop.predicted.values` from `startDate` |
| OpenAPS rigs | Careportal events | `openaps.iob` may be an array |
| xDrip+, Careportal | manual entries | only uploader battery |

The client keeps boluses, carbs and temp basal settings, drops notes, priming boluses and
entries with `isValid: false`, and marks SMBs and automatic boluses. It merges the device status
documents of one cycle: newer values win, and a document that lacks a value keeps the older one.

Basal and bolus are separate treatment kinds throughout the cache, Bluetooth relay and both
apps. Temp basals retain `absolute` (or `rate`) in U/h and `duration` in minutes. A zero rate
is retained; a zero duration is a cancellation. Percentage temps retain Nightscout's `percent`
adjustment (`0` unchanged, `-100` suspended), not an inferred U/h value. The semantics follow
[Nightscout's basal plugin](https://github.com/nightscout/cgm-remote-monitor/blob/master/lib/plugins/basalprofile.js).
These are reported settings, not measured delivered doses. No dose is calculated by multiplying
a rate by its requested duration, because it may have been cancelled or replaced early.

Both apps show basal square markers separately from bolus markers and show the latest basal
report with its age. This is historical information, not a claim that the last setting is still
active. The scheduled basal profile is not fetched or reconstructed. CareLink's
`AUTO_BASAL_DELIVERY` markers are retained as delivered basal pulses in U, separate from its
`INSULIN` boluses (including `AUTOCORRECTION`). Neither basal pulses nor manually logged basal
injections can become the "last bolus". IOB remains the value reported by the loop/pump.

Oref systems upload up to four forecast curves. The watch draws one: `COB` while carbs are on
board, otherwise `UAM`, then `IOB`, then `ZT`. Loop uploads a single curve. The curves are in
mg/dL, one point every 5 minutes, starting at the current glucose; the watch drops the points
within 2.5 minutes of the latest reading.

## Freshness rules

| Shown | Hidden when |
|---|---|
| IOB and COB | the loop has not reported for 30 minutes (the same threshold as Nightscout's own IOB plugin) |
| Loop forecast | it started more than 15 minutes ago |
| "Loop quiet for …" | the loop reported within the last 6 hours, but not within 30 minutes |

With "Loop (Nightscout)" chosen as the forecast, the watch never falls back to another model. A
missing or old loop forecast shows as "No fresh loop forecast".

## Fetching

Every refresh, about every 5 minutes:

- glucose since the latest cached reading minus 15 minutes (a whole day on the first run)
- treatments for the chart window again (3 hours or more), because carbs are often entered late
  or edited; older ones stay cached for a day
- device status since the last loop report, merged onto the cached state; the first run looks
  back 6 hours

On an iAPS site that is about 20 to 30 KB per refresh, mostly device status. The cache on the
watch is tagged with the source and server (`Settings.accountKey`). Data cached for another
source is never shown or used as history, even when a fetch for the old source finishes after
the switch.

## Testing

- `core/src/test/kotlin/glucowatch/core/NightscoutClientTest.kt` covers both APIs, authentication,
  the uploaders above, the merge and the freshness rules, with fixtures trimmed from a real site.
- `./gradlew -q --console=plain :core:run --args="--source nightscout --url <address> --hours 3 --predict"`
  prints what the watch would read. `NIGHTSCOUT_URL`, `NIGHTSCOUT_TOKEN` and `NIGHTSCOUT_API` in
  `.env` work too.
- `docs/screenshots.md` shows the result on a simulated watch, including a replay that makes a
  quiet loop look fresh.
