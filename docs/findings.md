# Findings — measured on the device

Environment, toolchain and **Surfaces A and B are verified on-device**. Surface C (on-watch raw
sensors) and Surface D (bulk export) are still untested. Each claim cites the run that produced it.

**Headline: Surface B works and recovered 13 months of history.** The Samsung Health Data SDK
runs on a non-Samsung phone under developer mode and returned real data for 8 of its 16 readable
types. Health Connect returned nothing, for a diagnosed reason.

## Environment under test

Verified by `adb` on 2026-10-30.

| | |
|---|---|
| Watch | Samsung Galaxy Watch 7 |
| Wear OS / One UI Watch version | _tbd — watch not yet connected_ |
| **Phone** | **POCO (Xiaomi) `2412DPC0AG`, HyperOS 3.0 / MIUI V816** |
| Android version | **16 (API 36)** |
| Samsung Health version | **7.1.0.053** ✅ (floor is 6.30.2) — updated 2026-10-21, `targetSdk=36` |
| Health Connect | **system module, controller v17** (Android 14+ ships it in-platform) |
| Galaxy Wearable | 2.2.72.26092361 |
| Watch7 plugin | 2.2.15.26071051 — watch is paired to this phone |
| Health Sensor Service version | _tbd_ (needs ≥ 1.3.0 for `SPO2_ON_DEMAND`) |
| Data SDK | v1.1.0 (AAR not yet obtained) |
| Sensor SDK | v1.4.1 (AAR not yet obtained) |

### ⚠️ The phone is not a Samsung

The plan assumed a Samsung phone. It is a **POCO/Xiaomi**. Samsung Health 7.1.0.053 is installed
and current, and the Watch7 plugin is present, so pairing and sync work. But this makes one thing
an open question that would otherwise have been a given:

- **Does Samsung Health's "Developer mode (Samsung Health Data SDK)" screen exist on a
  non-Samsung phone?** The tap-version-10× gesture is an app feature, so it probably does, but
  Surface B depends entirely on it. Check before investing further in the Data SDK.

Two consequences already confirmed:
- **Android 16 (API 36).** Both probes deliberately target SDK 35, which keeps us below Samsung's
  `READ_ADDITIONAL_HEALTH_DATA` requirement (Sensor SDK v1.4.0+ applies it to apps *targeting*
  16+). That choice now has a real device behind it.
- **HyperOS blocks `adb install`** with `INSTALL_FAILED_USER_RESTRICTED`. Needs Developer options
  → *Install via USB*, or sideload the APK from the device's own file manager.

## Toolchain

| | |
|---|---|
| JDK | Temurin 21.0.13 (Fedora 44 ships only JDK 25, which AGP rejects) |
| Gradle | 8.11.1 (`~/.local/opt`), wrapper generated |
| Android SDK | `~/Android/Sdk` — platform 35, build-tools 35.0.0 |
| adb | 1.0.41 / android-tools 37.0.0p1 |

All the versions guessed offline in `gradle/libs.versions.toml` resolved without changes:
AGP 8.7.3, Kotlin 2.0.21, connect-client 1.1.0-alpha10. `SkinTemperatureRecord` exists in that
client version.

### Build fixes needed on first compile

| Issue | Fix |
|---|---|
| `InstantaneousRecord` / `IntervalRecord` are `internal` in connect-client 1.1.0-alpha10 — no public supertype exposes `time`/`startTime` | Parse the timestamp back out of the reflected JSON payload, where `reflectToJson` has already rendered the `Instant` as ISO-8601 |
| `HealthConnectFeatures` is experimental | `@OptIn(ExperimentalFeatureAvailabilityApi::class)` on `backgroundReadFeatureStatus()` |

`:probe-phone:assembleDebug` and `:probe-watch:assembleDebug` (stubbed) both build clean.

## Run log

| Run | Probe | What it tested | Result |
|---|---|---|---|
| `20261030-031607` | phone | Health Connect sweep, 29 types, 30d | 0/29 returned data |
| `20261030-034132` | phone | Samsung Data SDK sweep, 16 types, 400d | **8/16 returned data, 20 MB** |

## Surface A — Health Connect: empty, and we know why

All 31 permissions were granted (29 record types + background + history, zero denied), the SDK
reported `SDK_AVAILABLE`, and the sweep completed with no errors. It returned **0 records across
all 29 types**.

**Root cause, established via `adb`:** Samsung Health holds **31 of 31 Health Connect permissions
denied** (`granted=false`). It has never been connected, so nothing has ever been written to
Health Connect. The probe read a genuinely empty store — this is not an API or permission fault
on our side.

Secondary finding: the user had not worn the watch recently, so even a working sync would have
found little in a 30-day window. Both facts had to be separated before the result meant anything.

**Still unanswered:** whether connecting Samsung Health *backfills* history into Health Connect or
only syncs forward from that moment. Samsung's wording ("when Samsung Health has new or updated
data, it writes to Health Connect") suggests forward-only. If so, Health Connect is useless for
historical recovery and only matters for future capture.

## Surface B — Samsung Health Data SDK: works, and it has the history

**Developer mode works on a non-Samsung phone.** This was an open risk (the phone is a
POCO/Xiaomi, not a Galaxy). Samsung's own service confirmed it in logcat:
`SHD#PrivilegedHealthService: dev.watched.probe.phone is verified`. All **16 readable types were
granted**, including the four that exist on no other surface.

### Data recovered (run `20261030-034132`, 400-day window)

| Type | Records | Per-minute samples | Oldest | Newest | Bytes/day of data |
|---|---:|---:|---|---|---:|
| `heart_rate` | 3,245 | **12,743** | 2025-09-25 | **2026-10-30** | 6.8 KiB |
| `floors_climbed` | 258 | — | 2025-09-25 | 2026-01-01 | 1003 B |
| `exercise` | 135 | — | 2025-09-25 | 2026-01-03 | 144.2 KiB |
| `sleep` | 45 | — | 2025-09-25 | 2025-12-27 | 4.3 KiB |
| `blood_oxygen` | 43 | 9,763 | 2025-09-25 | 2025-12-27 | 12.9 KiB |
| `skin_temperature` | 43 | 12,199 | 2025-09-25 | 2025-12-27 | 16.7 KiB |
| `energy_score` | 30 | — | 2025-09-25 | 2025-12-19 | 129 B |
| `user_profile` | 1 | — | — | — | — |

**Empty but permitted** (8): `blood_glucose`, `blood_pressure`, `body_composition`,
`body_temperature`, `nutrition`, `water_intake`, `sleep_apnea`,
`irregular_heart_rhythm_notification`. These need hardware events or manual entry that never
happened — absence of data, not absence of capability.

### `binning_data` is the real resolution

The record count badly understates what is there. Each `heart_rate` record is a **one-hour bucket**
carrying up to 60 `binning_data` entries at **per-minute** granularity:

```json
{"startTime":"2025-09-25T06:00:00Z","endTime":"2025-09-25T06:00:59Z",
 "heartRate":51.0,"min":50.0,"max":52.0}
```

Same shape for `blood_oxygen` and `skin_temperature`. Not every hour is full — 3,245 hourly
records hold 12,743 per-minute samples, not 194,700, so the watch was worn intermittently.

### Verified payload shapes

- `sleep`: `sessions[]` with **`stages[]`** (`stage`, `sleepStageOrdinal`, times), plus
  **`sleep_score`** (e.g. 59) and `sleep_duration` (ISO-8601 `PT6H6M`).
- `exercise`: `sessions[]` with `calories`, `count`, `countType` (`STRIDE`), `autoDetected`,
  `altitudeGain/Loss`; top level `exercise_type` (`WALKING`).
- `energy_score`: `total_score` (float, e.g. 79.88), one per day.
- `user_profile`: `height`, `weight`, `gender`, `date_of_birth`, `nickname`.
- Every record carries `_uid`, `_startTime`, `_endTime`, `_updateTime`, `_zoneOffset`,
  and `dataSource` as `_appId` / `_deviceId`.

### Three undeclared dependencies — the main integration trap

The Samsung AAR ships as a loose file with **no POM**, so none of its transitive dependencies
resolve. Each one fails at *runtime*, not compile time, as `NoClassDefFoundError`. Scanning the
extracted bytecode gave the complete list in one pass instead of discovering them one crash at a
time:

| Package | Status | Symptom if missing |
|---|---|---|
| `kotlinx.parcelize` | **must add** | `ReadDataRequest` fails to initialise |
| `com.google.gson` | **must add** | `PlatformInternalException(code=9003)` on every read |
| `kotlinx.coroutines` | already present | — |

Both are now wired into `probe-phone/build.gradle.kts` and `probe-watch/build.gradle.kts`.

## Questions answered so far

| # | Question | Answer |
|---|---|---|
| 2 | Is HRV reachable? | **Not via Surface A or B.** No HRV constant exists in the SDK (bytecode). Health Connect's `HeartRateVariabilityRmssdRecord` returned nothing. Remaining hopes: the export, or deriving it from raw IBI/PPG. |
| 4 | Are the Data SDK's exclusive types populated? | **Partly.** `energy_score` yes (30 days), `skin_temperature` yes (12,199 samples), **`sleep_score` yes** (inside sleep sessions). `sleep_apnea` and `irregular_heart_rhythm_notification` empty. |
| 5 | Can `STEPS` be read? | **No.** `DataType$StepsType` does not implement `Readable` — aggregate-only. Samsung's own permission sample is misleading. |
| 6 | Do background reads work? | Feature reports `FEATURE_STATUS_AVAILABLE` and the permission was granted. **An actual background read has not yet been confirmed.** |
| 10 | Which unmapped HC types carry data? | Moot while Health Connect is empty. Re-test after connecting Samsung Health. |

## Still open

1. **Surface C** — `SDK_POLICY_ERROR` smoke test has not run. The watch has not been connected.
2. **Surface D** — no export taken yet. This is the only remaining candidate source for HRV and stress.
3. **Health Connect backfill** — does connecting Samsung Health import history, or only sync forward?
4. **Background reads** — feature available, real unattended read unconfirmed.
5. **Developer-mode durability** — does it survive reboots and Samsung Health updates?
