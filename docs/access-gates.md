# Access gates

What stands between `watched` and each Samsung data surface, and how we get through it.

Researched 2026-10-30. Every claim here is sourced; nothing has been confirmed against the
actual device yet. See [capability-matrix.md](capability-matrix.md) for the per-type inventory
and [findings.md](findings.md) for measured results.

---

## Summary

| Surface | Where it runs | Gate | Our path |
|---|---|---|---|
| A. Health Connect | Phone | None (standard AndroidX API) | Just request permissions |
| B. Samsung Health Data SDK | Phone | Partnership for *distribution* and *writes* | **Developer Mode for Data Read** — no partnership |
| C. Samsung Health Sensor SDK | Watch (Wear OS) | Partnership for *distribution* | **Developer mode** — no partnership |
| D. "Download personal data" export | Phone, manual | None | Manual in-app action, then `adb pull` |

The headline: **a sideloaded, read-only, personal app needs no Samsung partnership on any
surface.** Both Samsung SDKs have a developer mode that covers exactly our case. The cost is
that developer mode is explicitly "ONLY intended for testing or debugging your app. It is NOT
for app users" — so durability of that mode is an open risk (see below).

---

## A. Health Connect

No Samsung involvement. Standard `androidx.health.connect:connect-client`.

**Requirements**
- Android SDK 28 (Pie) or higher for the Health Connect APIs.
- On Android 14+, Health Connect is a system app. API 1.1.0+ supports that.
- Samsung Health 6.22.5 or later (October 2022) for Samsung→Health Connect sync.

**User setup, done once in Samsung Health**
1. Samsung Health → Settings → **Health Connect**. Install Health Connect if prompted.
2. Tap **Get started** on first access.
3. Select the data to share and tap **Allow**. Read and write permissions are granted separately.
4. Samsung Health → Settings → **Sync with Samsung Cloud** → On.
5. Verify under Settings → Health Connect → **Data and access** → Browse data.

Permissions can alternatively be granted from Android Settings → Health Connect → App
permissions → Samsung Health. If granted that way, launch Samsung Health afterwards.

**Gates inside the API**
- **Background reads** require `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`.
  Google's docs contradict themselves here: the sync guide says "Apps can only read data from
  Health Connect while they are in the foreground", but then describes the background
  permission, and the read-data guide documents a `CoroutineWorker` +
  `PeriodicWorkRequestBuilder` pattern gated on
  `HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND` being `FEATURE_STATUS_AVAILABLE`.
  **Resolve empirically.** The minimum Health Connect version for this permission is not stated.
- **History beyond 30 days** requires `android.permission.health.READ_HEALTH_DATA_HISTORY`
  (constant `PERMISSION_READ_HEALTH_DATA_HISTORY`). Without it, "an attempt to read records
  older than 30 days results in an error".
  - Android 14+: no limit on reading data *your own app wrote*; 30-day limit on other apps' data.
  - Android 13 and lower: 30-day limit on any data.
  - Uninstalling the app revokes all permissions; the 30-day clock restarts from the new grant.

**Not a gate but a trap**
- There are **no push notifications** for new data. Sync is polled via
  `getChangesToken(ChangesTokenRequest(recordTypes = ...))` → `getChanges(token)`.
  An **unused changes token expires in 30 days**, so a long outage loses the incremental
  path silently. Use one token per data type, not a bulk token, so a single revoked
  permission does not throw for all types.
- `DeletionChange` carries only the record `id`, not the type. We must persist Health Connect
  ids ourselves to interpret deletions.
- On Android 14 with SDK Extension 20+, on-device steps use a Synthetic Package Name instead of
  `android` in `DataOrigin` from the June 2026 update onward. If filtering by origin, query both
  `android` and the device SPN from `getCurrentDeviceDataSource`.

---

## B. Samsung Health Data SDK (phone)

v1.1.0, released 2026-03-12.

**Requirements**
- Samsung Health **6.30.2 or later**.
- Android **10 (API level 29)** or later.
- **Java 17 or later.**
- **Emulators are not supported.**
- Distributed as `samsung-health-data-api.aar` inside the SDK zip's `/libs`. There are **no
  published Gradle coordinates** — the AAR is imported manually.

**The gate, verbatim from Samsung's process page**

> "Please submit partner request before your app distribution. Your app's information including
> the app package name and signature (SHA-256) can be registered in the Samsung Health's system
> after an approval."

> "Otherwise, the app using the Samsung Health Data SDK works only with the developer mode
> turned on."

And for writes specifically:

> "To write health data to Samsung Health from an app, the access code is required. You can
> request partnership ... and receive the access code after an approval."

**Reading our own data from a sideloaded debug build needs neither.** The SDK validates package
name and signature against registered partner apps; a debug-signed build therefore only works
with developer mode on. That is acceptable — and in fact required — for this project.

**Enabling Developer Mode for Data Read**
1. Samsung Health → **⋮** → Settings → **About Samsung Health**.
2. Tap the version line **10 or more times** until **Developer mode (Samsung Health Data SDK)** appears.
3. Select it and agree to the Notice.
4. Turn on **Developer Mode for Data Read**.

Samsung's own warning: "The Samsung Health developer mode is ONLY intended for testing or
debugging your app. It is NOT for app users."

**Permission model**
```kotlin
val perm = Permission.of(DataTypes.HEART_RATE, AccessType.READ)   // or AccessType.WRITE
val store = HealthDataService.getStore(context)
val granted = store.getGrantedPermissions(permSet)                 // may throw HealthDataException
if (!granted.containsAll(permSet)) store.requestPermissions(permSet, activity)
```
"Accessing health data requires the user's permissions for every data type."

**Error handling.** `HealthDataException` is the parent; subclasses are `AuthorizationException`,
`InvalidRequestException`, `PlatformInternalException`, `ResolvablePlatformException`. For the
last, check `error.hasResolution` and call `error.resolve(activity)`. Its error codes:

| Code | Meaning |
|---|---|
| `ERR_PLATFORM_NOT_INSTALLED` | Samsung Health is not installed |
| `ERR_OLD_VERSION_PLATFORM` | Samsung Health too old for this SDK function |
| `ERR_PLATFORM_DISABLED` | Samsung Health installed but disabled |
| `ERR_PLATFORM_NOT_INITIALIZED` | Installed, but user never completed setup / T&C |

---

## C. Samsung Health Sensor SDK (watch, Wear OS)

v1.4.1, released 2025-08-20. This is the raw-signal surface and the reason the project can do
anything Samsung Health itself does not.

**Requirements**
- Galaxy Watch4 series or later, running "Wear OS Powered by Samsung". Watch 7 qualifies.
- **Emulators are not supported.**
- No Wear OS version floor is stated.

**Naming confusion.** `developer.samsung.com/health/privileged/overview.html` serves a page
titled **Samsung Health Sensor SDK** with no "Privileged" content anywhere. "Privileged Health
SDK" and "Health Sensor SDK" appear to be the same product under two names. Treat the Sensor SDK
docs and release notes as authoritative.

**The gate, verbatim from the process page**

> "Please submit partner request before your app distribution. Your app's information including
> the app package name and signature (SHA-256) can be registered in the Samsung Health's system
> after an approval."

> "Otherwise, the app using the Samsung Health Sensor SDK works only with the developer mode
> turned on."

So: the SDK download itself is **not gated** (v1.4.1, 70.8 KB). Running on your own watch needs
**developer mode**. Only distribution needs partnership. The pages say nothing about whether
individual developers or personal-use apps are eligible for partnership, name no specific
program, and state no regional restriction.

**The residual risk — `SDK_POLICY_ERROR`.** From the v1.0.0-alpha2 release note:

> "The SDK policy has been applied. Tracking watch sensor data with the SDK is available only for
> the tracking types within the registered scope. If `SDK_POLICY_ERROR` occurs, talk to your
> Samsung contact point."

The docs **do not say whether this scope check applies in developer mode.** If it does, the raw
sensor branch is blocked without partnership. This is the single highest-risk unknown in the
project and `probe-watch` must test it before any other watch work.

**Android 16 permission change** (v1.4.0, 2025-07-31):

> "Required permissions to use each `HealthTrackerType` have been changed for targeting Android 16
> (API level) or higher."

Targeting Android 16+ brings in
`com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` for most
trackers. `ACCELEROMETER_CONTINUOUS` is not in that list. The docs do not state what the
permission gates or whether it is a restricted permission. The extracted table was flattened, so
**the per-tracker permission pairing is unverified** — confirm against the SDK's own docs in the
downloaded zip.

---

## D. "Download personal data" export (manual)

No gate beyond Samsung account verification, but **no automation either**.

**Path:** Samsung Health → **⋮** → Settings → **Download personal data** → Download, then verify
the Samsung account by fingerprint or password.

**Output:** `/sdcard/Download/Samsung Health/samsunghealth_<account>_<timestamp>/`. One report
cites ~236 MB across ~23,000 files. Retrieve with:

```
adb pull "/sdcard/Download/Samsung Health" ./export-raw/
```

**Not scriptable.** The in-app account verification means a person starts every export. Only the
copy off the device can be automated. Treat this as an occasional backfill, not a pipeline.

Conflicting reports exist on whether delivery is an email link or an on-device folder, and on the
exact menu path (some versions put it under My page → ⋮ → Settings). Available date ranges and
export duration are undocumented. Settle all of it with one real run.

---

## Open risks

1. **`SDK_POLICY_ERROR` in developer mode.** Could block all raw sensor access. Test first.
2. **Developer-mode durability.** Both Samsung developer modes are debug features. Unknown whether
   they survive reboots, Samsung Health app updates, or simply elapsed time. If either resets,
   unattended long-run collection is unreliable and the architecture must assume gaps.
3. **Health Connect background reads.** Google's docs contradict each other. If background reads
   do not work, every collection becomes foreground-only and the collector needs a different shape.
4. **Changes-token expiry.** A 30-day outage silently breaks incremental sync. The eventual
   collector needs a full-resync fallback.
5. **Samsung Health app updates** can change the synced data scope: "A synchronized data scope can
   be changed depending on the Samsung Health version." The matrix will need periodic re-verification.

## Sources

- Samsung Health Data SDK: [overview](https://developer.samsung.com/health/data/overview.html) ·
  [data types](https://developer.samsung.com/health/data/guide/features/data-types.html) ·
  [permissions](https://developer.samsung.com/health/data/guide/hello-sdk/permission-request.html) ·
  [release notes](https://developer.samsung.com/health/data/release-note.html)
- Samsung Health Sensor SDK: [overview](https://developer.samsung.com/health/privileged/overview.html) ·
  [release notes](https://developer.samsung.com/health/sensor/release-note.html) ·
  [data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html)
- Samsung ↔ Health Connect: [blog](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect) ·
  [BIA via Health Connect](https://developer.samsung.com/health/blog/en/health/blog/reading-body-composition-data-with-galaxy-watch-via-health-connect-api)
- Health Connect: [data types](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types) ·
  [read data](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data) ·
  [sync](https://developer.android.com/health-and-fitness/health-connect/sync-data)
