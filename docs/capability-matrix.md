# Capability matrix — Samsung Galaxy Watch 7

What data `watched` can obtain, from which surface, at what fidelity.

**Status legend**
- `unverified` — documented by Samsung/Google, not yet confirmed on this device
- `verified` — confirmed by a probe run, with evidence in [findings.md](findings.md)
- `unavailable` — probe confirmed it is absent or empty on this device
- `blocked` — exists, but a gate we cannot pass

Surfaces are defined in [access-gates.md](access-gates.md):
**A** Health Connect · **B** Samsung Health Data SDK · **C** Samsung Health Sensor SDK (on-watch) ·
**D** "Download personal data" export.

> Everything below is `unverified` as of 2026-10-30. This file is the target of the probe work;
> no row should remain `unverified` when Phase 0 closes.

---

## 1. Master matrix, by metric

`•` = documented on that surface. `—` = not offered. `?` = plausible but undocumented, probe must decide.

| Metric | A · Health Connect | B · Data SDK | C · Sensor SDK | D · Export | Best source | Status |
|---|---|---|---|---|---|---|
| Steps | `StepsRecord` • | aggregate only • | — | `step_count`, `step_daily_trend`, `pedometer_step_count` • | A | unverified |
| Heart rate (samples) | `HeartRateRecord` • | `HEART_RATE` • | `HEART_RATE_CONTINUOUS` 1 Hz • | `heart_rate` • | C for cadence, A for history | unverified |
| Resting heart rate | `RestingHeartRateRecord` ? | — | — | `resting_heart_rate` • | D, maybe A | unverified |
| **HRV** | `HeartRateVariabilityRmssdRecord` ? | **—** | derive from IBI / raw PPG | `hrv` • (columns disputed) | **unresolved** | unverified |
| Inter-beat interval (IBI) | — | — | in `HEART_RATE_CONTINUOUS` batches • | — | C only | unverified |
| Raw PPG (green/IR/red) | — | — | `PPG_CONTINUOUS` 25 Hz, `PPG_ON_DEMAND` 100 Hz • | — | C only | unverified |
| Raw accelerometer | — | — | `ACCELEROMETER_CONTINUOUS` 25 Hz • | — | C only | unverified |
| ECG | — | — | `ECG_ON_DEMAND` 500 Hz • | — | C only | unverified |
| SpO2 / blood oxygen | `OxygenSaturationRecord` • | `BLOOD_OXYGEN` • | `SPO2_ON_DEMAND` • | `oxygen_saturation` • | A | unverified |
| Sleep session | `SleepSessionRecord` • | `SLEEP` • | — | `sleep` • | B | unverified |
| Sleep stages | `SleepSessionRecord.stages` • | within `SLEEP` • | — | `sleep_stage` • | B | unverified |
| **Sleep score** | — | **• (read-only)** | — | ? | B only | unverified |
| **Sleep apnea** | — | **• (read-only, v1.1.0+)** | — | ? | B only | unverified |
| **Energy score** | — | **• (read-only)** | — | ? | B only | unverified |
| **Irregular heart rhythm** | — | **• (read-only, v1.1.0+)** | — | ? | B only | unverified |
| Skin temperature | `SkinTemperatureRecord` ? (feature-flagged) | **• (read-only)** | `SKIN_TEMPERATURE_CONTINUOUS` / `_ON_DEMAND` • | ? | B or C | unverified |
| Body temperature | `BodyTemperatureRecord` — | `BODY_TEMPERATURE` • (v1.0.0+) | — | — | B | unverified |
| **Stress** | — | — | — | ? (claimed, unconfirmed) | **unresolved** | unverified |
| Respiratory rate | `RespiratoryRateRecord` ? | — | — | `respiratory_rate` • | D, maybe A | unverified |
| Body composition / BIA | `BodyFatRecord`, `BasalMetabolicRateRecord`, `WeightRecord`, `HeightRecord` • | `BODY_COMPOSITION` • | `BIA_ON_DEMAND` • | `weight` • (extra columns disputed) | B or C | unverified |
| Exercise sessions | `ExerciseSessionRecord` + `TotalCaloriesBurned`/`Distance`/`Power`/`Speed`/`Vo2Max` • | `EXERCISE` (incl. `vo2Max`) • | — | `exercise`, `.location`, `.route` • | B | unverified |
| Floors climbed | — | `FLOORS_CLIMBED` • | — | — | B | unverified |
| Sweat loss | — | — | `SWEAT_LOSS` • | — | C only | unverified |
| Activity summary / goals | — | aggregate • | — | — | B only | unverified |
| User profile (sex, DOB, height, weight, nickname) | — | • (read-only) | — | — | B | unverified |
| EDA / electrodermal | — | — | **Watch 8+ only** | — | none | unavailable |
| Multi-frequency BIA | — | — | **Watch 8+ only** | — | none | unavailable |

### What this table says

1. **Surface B is not optional.** Energy score, sleep score, sleep apnea, and irregular heart
   rhythm exist on **no other surface**. If those matter, the Data SDK and its developer mode
   are required.
2. **Surface C is the only raw signal.** PPG, accelerometer, ECG, and IBI exist nowhere else.
   Everything downstream that needs sub-minute resolution depends on the watch app working.
3. **HRV and stress are the two genuine unknowns.** Neither has a confirmed source. HRV is
   absent from the Data SDK entirely, absent from Samsung's Health Connect mapping, and the
   export's `hrv` column layout is disputed. Worst case we derive HRV ourselves from IBI or raw
   PPG — which is another argument for Surface C.
4. **Health Connect is the convenience layer, not the rich one.** It is the only ungated surface
   and the right default, but it is a strict subset.

---

## 2. Surface A — Health Connect

Samsung's own published mapping. Anything not in this table is not guaranteed to arrive, even if
Health Connect defines a record type for it.

| Samsung Health data | Health Connect type | Status |
|---|---|---|
| All steps | `StepsRecord` | unverified |
| Blood glucose | `BloodGlucoseRecord` | unverified |
| Blood oxygen | `OxygenSaturationRecord` | unverified |
| Blood pressure | `BloodPressureRecord` | unverified |
| Exercise, session | `ExerciseSessionRecord` | unverified |
| Exercise, calories | `TotalCaloriesBurnedRecord` | unverified |
| Exercise, distance | `DistanceRecord` | unverified |
| Exercise, heart rate | `HeartRateRecord` | unverified |
| Exercise, power | `PowerRecord` | unverified |
| Exercise, speed | `SpeedRecord` | unverified |
| Exercise, VO2max | `Vo2MaxRecord` | unverified |
| Heart rate | `HeartRateRecord` | unverified |
| Nutrition | `NutritionRecord` | unverified |
| Sleep session / sleep stage | `SleepSessionRecord` | unverified |
| Weight | `WeightRecord` | unverified |
| Weight, body fat | `BodyFatRecord` | unverified |
| Weight, basal metabolic rate | `BasalMetabolicRateRecord` | unverified |
| Weight, height | `HeightRecord` | unverified |

**Explicitly not synced:** "The Samsung Health's activity tracker data are not synchronized with
Health Connect."

**Defined by Health Connect but absent from Samsung's mapping** — the probe requests these anyway,
because absence from a blog table is not proof of absence in practice:
`HeartRateVariabilityRmssdRecord`, `RestingHeartRateRecord`, `SkinTemperatureRecord`
(needs `FEATURE_SKIN_TEMPERATURE`), `RespiratoryRateRecord`, `BodyWaterMassRecord`,
`BoneMassRecord`, `LeanBodyMassRecord`, `ActiveCaloriesBurnedRecord`, `FloorsClimbedRecord`,
`StepsCadenceRecord`, `ElevationGainedRecord`, `MindfulnessSessionRecord`.

**Sync timing.** Data moves watch→phone→Health Connect when the watch reconnects to the phone,
when Samsung Health's home screen is opened, or on pull-to-refresh. Samsung states: "For battery
conservation reasons, continuous heart rate data from the Galaxy Watch is not sent to the Samsung
Health application on the smartphone immediately. However, manual heart rate measurements on the
watch are synchronized immediately." **Measure the real lag per type.**

---

## 3. Surface B — Samsung Health Data SDK

Full type list with documented operations. `R` read · `W` insert/update/delete · `A` aggregate ·
`C` read changes.

| Data type | Ops | Notes | Status |
|---|---|---|---|
| Blood glucose | R W C | | unverified |
| Blood oxygen | R W C | | unverified |
| Blood pressure | R W C | | unverified |
| Body composition | R W C | | unverified |
| Body temperature | R W C | added v1.0.0 | unverified |
| **Energy score** | R C | **read-only, B-exclusive** | unverified |
| Exercise | R W A C | `vo2Max` added v1.0.0 | unverified |
| Exercise location | — | permission only | unverified |
| Floors climbed | R W A C | | unverified |
| Heart rate | R W A C | cadence undocumented | unverified |
| **Irregular heart rhythm notification** | R C | **read-only, added v1.1.0, B-exclusive** | unverified |
| Nutrition | R W A C | | unverified |
| **Skin temperature** | R C | **read-only** | unverified |
| Sleep | R W A C | + "read associated data"; **sleep score readable, not writable** | unverified |
| **Sleep apnea** | R C | **read-only, added v1.1.0, B-exclusive** | unverified |
| Steps | **A only** | docs show no read row — but the permission sample requests `READ`. **Contradiction, probe it.** | unverified |
| Water intake | R W A C | | unverified |
| Active calories burned goal | A | | unverified |
| Active time goal | A | | unverified |
| Activity summary | A | | unverified |
| Nutrition goal | A | | unverified |
| Sleep goal | **A only** | same `READ` contradiction as Steps | unverified |
| Step goal | A | | unverified |
| Water intake goal | A | | unverified |
| User profile | R | sex, DOB, height, weight, nickname | unverified |

**HRV does not appear anywhere in this list.** The API reference has not been read; if HRV exists
it is undocumented on the data-types page.

Field-level schemas (what a sleep or heart-rate record actually contains, sample granularity,
whether series data is exposed) are **not documented on the pages read**. The probe must discover
them by dumping records.

---

## 4. Surface C — Samsung Health Sensor SDK (on-watch)

| Tracker | Raw/processed | Documented rate | Watch 7 | Status |
|---|---|---|---|---|
| `ACCELEROMETER_CONTINUOUS` | raw x,y,z | 25 Hz | expected | unverified |
| `PPG_CONTINUOUS` green/IR/red | raw | 25 Hz | expected | unverified |
| `HEART_RATE_CONTINUOUS` | processed, **incl. IBI** | 1 Hz | expected | unverified |
| `SKIN_TEMPERATURE_CONTINUOUS` | processed | not stated | Watch5+ | unverified |
| `ECG_ON_DEMAND` | raw | 500 Hz | expected | unverified |
| `PPG_ON_DEMAND` green/IR/red | raw | 100 Hz | expected | unverified |
| `BIA_ON_DEMAND` | processed | not stated | Watch4+ | unverified |
| `SPO2_ON_DEMAND` | processed | not stated | needs Health Sensor Service ≥ v1.3.0 | unverified |
| `SKIN_TEMPERATURE_ON_DEMAND` | processed | not stated | Watch5+ | unverified |
| `SWEAT_LOSS` | processed | 1 point per running exercise | expected | unverified |
| `EDA_CONTINUOUS` | raw | 1 Hz | **Watch8+ — not on Watch 7** | unavailable |
| `MF_BIA_ON_DEMAND` | processed, 5/10/50/250 kHz | not stated | **Watch8+ — not on Watch 7** | unavailable |

**Rules.** On-demand trackers run one at a time, in the foreground, for roughly 30 seconds. IBI
values are carried in the first data point of each `HEART_RATE_CONTINUOUS` batch.

**Volume to expect.** `PPG_CONTINUOUS` (3 channels) + `ACCELEROMETER_CONTINUOUS` (3 axes) at 25 Hz
is ~150 values/s. Packed binary that is roughly **100 MB/day**; naive JSON lines several times
more. One `ECG_ON_DEMAND` run at 500 Hz × 30 s is 15,000 samples. These are estimates — the probe
measures the real figures, and they decide the transport and storage design.

---

## 5. Surface D — "Download personal data" export

Filenames below come from a third-party parser's fixtures and carry a `.202605` month suffix that
is probably a fixture artifact rather than Samsung's naming. **Confirm against a real export.**

`com.samsung.health.exercise` · `.exercise.location` · `.exercise.route` · `.heart_rate` ·
`.hrv` · `.oxygen_saturation` · `.respiratory_rate` · `.resting_heart_rate` · `.sleep` ·
`.sleep_stage` · `.step_count` · `.weight` · `com.samsung.shealth.step_daily_trend` ·
`com.samsung.shealth.tracker.pedometer_step_count`

Plus binary/JSON blobs at `jsons/<datatype>/<0-f>/<uuid>.binning_data.json`.

**Known headers** (from fixtures, possibly synthetic):
- `com.samsung.health.hrv.csv` → `start_time,end_time,hrv,source_name` — **a single `hrv` value,
  no `sdnn`/`rmssd` columns.** This directly contradicts the claim that the export carries
  30-second-window SDNN and RMSSD.
- `com.samsung.health.heart_rate.csv` → preceded by a **non-header preamble line**
  (`com.samsung.health.heart_rate,7006003,6`), then `start_time,time_offset,create_sh_ver,heart_rate,source_name`.
- `com.samsung.health.weight.csv` → `start_time,weight,unit,source_name` — **no body-fat, muscle,
  or water columns** in the fixture, contradicting the richer BIA claim.

**Disputed, resolve with one real export:**
- Does a stress file exist at all? No filename found in any source.
- Does `hrv` carry `sdnn`/`rmssd`, or one aggregate value?
- Does `weight` carry `body_fat_mass`, `skeletal_muscle`, `fat_free_mass`, `total_body_water`,
  `vfa_level`, `basal_metabolic_rate`?
- Is there a skin-temperature file?
- Is there a sleep score column?
- What do the `binning_data.json` blobs actually contain?

---

## 6. Questions Phase 0 must answer

1. Does `SDK_POLICY_ERROR` fire in developer mode? (Blocks all of Surface C if yes.)
2. Is HRV reachable anywhere, or must we derive it from IBI / raw PPG?
3. Is stress reachable anywhere?
4. Are the Data SDK's exclusive types (energy score, sleep score, sleep apnea, IHRN) actually
   populated on this watch, or merely defined?
5. Can `STEPS` be read, or is it genuinely aggregate-only?
6. Do Health Connect background reads work?
7. What is the real watch→Health Connect latency per type?
8. What is the real MB/day and battery cost of continuous raw capture?
9. Do the two developer modes survive reboots and app updates?
10. Which Health Connect types outside Samsung's mapping table actually carry data?
