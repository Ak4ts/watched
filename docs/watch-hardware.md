# Galaxy Watch 7 — physical sensor inventory

Read directly from the watch's sensor HAL on 2026-10-30 via
`adb shell dumpsys sensorservice`. **No SDK required** — this is the hardware itself, so it is
ground truth rather than what any SDK chooses to expose.

| | |
|---|---|
| Model | `SM-L300` (codename `fresh7bs`) |
| OS | Wear OS on **Android 16 (API 36)**, security patch 2026-02-05 |
| Samsung Health (watch) | 6.32.0.001 |
| **Health Sensor Service** | **1.7.00.05** (`SPO2_ON_DEMAND` needs ≥ 1.3.0 — satisfied) |

## The inventory

`perm: n/a` = usable by a normal app. `SSENSOR` = `com.samsung.permission.SSENSOR`, a
system-level permission **not grantable to a third-party app** — those sensors are reachable only
through Samsung's own SDK, if at all.

| Sensor | Android type | Rate | Permission |
|---|---|---|---|
| LSM6DSV Accelerometer | `accelerometer(1)` | 6.25–100 Hz, FIFO 300 | — |
| LSM6DSO Gyroscope | `gyroscope(4)` | 6.25–100 Hz | — |
| Gyroscope Uncalibrated | `gyroscope_uncalibrated(16)` | 6.25–100 Hz | — |
| Linear Acceleration | `linear_acceleration(10)` | 6.25–100 Hz | — |
| Rotation Vector | `rotation_vector(11)` | 5–100 Hz | — |
| Game Rotation Vector | `game_rotation_vector(15)` | 5–100 Hz | — |
| AK09918C Magnetometer | `magnetic_field(2)` + uncalibrated `(14)` | 6.25–100 Hz | — |
| LPS28DFW Barometer | `pressure(6)` | 1–10 Hz | — |
| STK31E15 Light | `light(5)` | 1–10 Hz | — |
| **Offbody Detector** | `low_latency_offbody_detect(34)` | on-change, wakeUp | — |
| Wrist tilt gesture | `wrist_tilt_gesture(26)` | trigger, wakeUp | — |
| Device orientation | `device_orientation(27)` | on-change, wakeUp | — |
| Step Detector | `step_detector(18)` | trigger | `ACTIVITY_RECOGNITION` |
| Step Counter | `step_counter(19)` | ≤1 Hz, FIFO 600 | `ACTIVITY_RECOGNITION` |
| **Samsung HR Raw** | `com.samsung.sensor.hr_raw(69682)` | **25 Hz, FIFO 600** | **`android.permission.health.READ_HEART_RATE`** |
| Samsung Altitude | `com.samsung.sensor.altitude(69658)` | trigger | — |
| AFE4510 ECG | `com.samsung.sensor.ecg(69669)` | 100 Hz | `SSENSOR` |
| AFE4510 BIA | `com.samsung.sensor.bia(69680)` | 100 Hz | `SSENSOR` |
| Samsung HR Raw Fac / Fac2 | `(69683)` / `(69684)` | 100 Hz, FIFO 600 | `SSENSOR` |
| Accelerometer 32G | `com.samsung.sensor.accelerometer_32g(69657)` | 6.25–100 Hz | `SSENSOR` |
| Thermistor | `com.samsung.sensor.thermistor(69656)` | on-change | `SSENSOR` |
| GPS Batch | `com.samsung.sensor.gps_batch(69633)` | 0.01–1 Hz | `SSENSOR` |
| Movement | `com.samsung.sensor.movement(69650)` | on-change, FIFO 100 | `SSENSOR` |
| Wrist down | `com.samsung.sensor.wrist_down(69655)` | trigger | `SSENSOR` |
| SContext (sensor hub) | `com.samsung.sensor.sensorhubs(69632)` | 50 Hz, data-injection | `SSENSOR` |
| LightCCT | `com.samsung.sensor.light_cct(65587)` | 5 Hz | `SSENSOR` |
| AutoBrightness / Aod AutoBrightness | Samsung private | 10 Hz | `SSENSOR` |

## Two findings worth acting on

**1. Raw PPG may not need the Sensor SDK at all.**
`Samsung HR Raw` runs at 25 Hz — the same rate the Sensor SDK documents for `PPG_CONTINUOUS` —
but its declared permission is `android.permission.health.READ_HEART_RATE`, an ordinary runtime
permission, **not** `SSENSOR`. If a plain `SensorManager.getDefaultSensor(69682)` can open it,
raw PPG is reachable without Samsung's Sensor SDK, its AAR, or its partner programme. Worth
testing before committing to the SDK path. *(Unverified: a declared HAL permission is not proof
the platform will hand the sensor to a third-party app.)*

**2. The hardware offers things the Sensor SDK does not.**
Notably a **gyroscope** (100 Hz, no permission) and a **barometer**, **magnetometer** and
**offbody detector** — none of which appear in the Sensor SDK's tracker list. The offbody
detector is directly useful: it tells us when the watch is *not* being worn, which is exactly the
gap that made Phase 0's 3,245 hourly records hold only 12,743 per-minute samples.

**Rate discrepancy to resolve:** the HAL reports ECG at **100 Hz**; the Sensor SDK documents
`ECG_ON_DEMAND` at **500 Hz**. One of the two is wrong, or the SDK upsamples/uses a different
path.

## Still blocked

`libs/samsung-health-sensor-api.aar` is absent — only the Data SDK was downloaded. Until it
arrives, `probe-watch` builds against its stub and the `SDK_POLICY_ERROR` smoke test cannot run.

Wireless debugging also drops when the watch screen sleeps; the screen must stay awake for an
adb session.
