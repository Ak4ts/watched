# Findings — measured on the device

Environment and toolchain are verified. **No probe has run yet**, so no data-type row is
answered — every row in [capability-matrix.md](capability-matrix.md) is still `unverified`.
Each claim below cites how it was established.

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

| Run | Date | Probe | What it tested | Result |
|---|---|---|---|---|
| — | — | — | — | — |

## Results

_Populated by `tools/` from the probe dumps._

## Open risks still unresolved

_Carried forward from [access-gates.md](access-gates.md) until closed._
