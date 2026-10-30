# watched

Personal telemetry from a **Samsung Galaxy Watch 7** — into a data lake, through rules and AI
hooks, out as WhatsApp alerts.

**Status: Phase 0 — capability mapping.** No collector yet. Right now the job is finding out
exactly what Samsung will hand over, through which door, at what fidelity, and with what gate in
front of it. Everything here is a diagnostic tool in service of that.

---

## Start here

| Document | What it answers |
|---|---|
| [docs/access-gates.md](docs/access-gates.md) | What stands between us and each data surface, and how we get through |
| [docs/capability-matrix.md](docs/capability-matrix.md) | Every data type × every surface, and whether it is verified |
| [docs/findings.md](docs/findings.md) | What the watch **actually** returned. Empty until the probes run. |

The short version: there are four surfaces, and **a sideloaded read-only personal app needs no
Samsung partnership on any of them.** Both Samsung SDKs have a developer mode that covers
exactly this case.

| Surface | Where | Gives us | Gate |
|---|---|---|---|
| **A** Health Connect | phone | 18 derived types | none |
| **B** Samsung Health Data SDK | phone | + energy score, sleep score, sleep apnea, skin temp, IHRN | developer mode |
| **C** Samsung Health Sensor SDK | **watch** | raw PPG 25 Hz, accel 25 Hz, ECG 500 Hz, IBI, BIA | developer mode |
| **D** "Download personal data" | phone, manual | deep history | manual export |

---

## Layout

```
shared/       models, the JSONL sink, the reflection-based dumper
probe-phone/  Android app — Surfaces A and B
probe-watch/  Wear OS app — Surface C
tools/        stdlib-only Python that turns dumps into findings tables
docs/         the actual deliverable of this phase
libs/         Samsung AARs go here (gitignored, not redistributable)
```

Both probes are **throwaway diagnostics**, not the eventual collector. They optimise for
completeness of output over elegance. Record payloads are dumped via reflection rather than typed
serialisers on purpose: we do not yet know the field schemas, and discovering them is the point.

---

## Prerequisites

Not yet installed on this machine:

- **Android Studio** — brings the Android SDK and a bundled JDK 21. Needed because Fedora 44
  ships only JDK 25, which current AGP does not support.
- **adb** — `sudo dnf install android-tools`
- **Samsung Health Data SDK** → `libs/samsung-health-data-api.aar`
- **Samsung Health Sensor SDK** → `libs/samsung-health-sensor-api.aar`

The Samsung AARs have no Gradle coordinates; they are downloaded by hand from
developer.samsung.com. **The project builds without them** — each module detects the missing AAR
and compiles a stub instead, so the Health Connect sweep is runnable before either SDK arrives.

```
./gradlew :probe-phone:samsungSdkStatus
./gradlew :probe-watch:sensorSdkStatus
```

> ⚠️ The Samsung-dependent source in `src/samsung/` was written against the documented API shape
> but **has never been compiled** — the AARs were not available when it was written. Each file
> carries a header saying which parts are verified and which are provisional. Expect to fix them
> on first sync; the compiler will find every mistake.

---

## Running the probes

### Device setup

**Phone** — Samsung Health → ⋮ → Settings → About Samsung Health → tap the version line 10×
→ *Developer mode (Samsung Health Data SDK)* → **Developer Mode for Data Read: ON**.

Also enable Health Connect sync: Samsung Health → Settings → Health Connect → Get started →
allow the data types.

**Watch** — enable developer mode, then sideload `probe-watch` over adb (wireless debugging).

### Order of operations

1. **`probe-watch` button 1 — the policy smoke test.** Everything raw depends on it. Samsung
   documents an `SDK_POLICY_ERROR` scope check but never says whether it applies in developer
   mode. If it fires, Surface C is closed without a partnership and the project re-plans around
   Surfaces A + B. **Run this before building anything else.**
2. `probe-phone` buttons 1–5 — Health Connect sweep, history wall, changes token.
3. `probe-phone` button 6, then leave it alone for an hour — the background-read question.
4. One "Download personal data" export, pulled and inspected.
5. `probe-watch` buttons 2–7 — the full tracker sweep, with volume and battery measured.

### Pulling and analysing

```sh
adb pull /sdcard/Android/data/dev.watched.probe.phone/files/dumps ./dumps
adb -s <watch> pull /sdcard/Android/data/dev.watched.probe.watch/files/dumps ./dumps-watch
adb pull "/sdcard/Download/Samsung Health" ./export-raw

python3 tools/analyze_dumps.py ./dumps ./dumps-watch
python3 tools/inspect_export.py ./export-raw
```

Paste the output into `docs/findings.md` and flip the matching rows in
`docs/capability-matrix.md` from `unverified`.

---

## Phase 0 is done when

`docs/capability-matrix.md` has no `unverified` rows and `docs/findings.md` answers, with
measured numbers:

1. Does `SDK_POLICY_ERROR` fire in developer mode?
2. Is HRV reachable anywhere, or must we derive it from IBI / raw PPG?
3. Is stress reachable anywhere?
4. Are the Data SDK's exclusive types actually populated, or merely defined?
5. Can `STEPS` be read, or is it genuinely aggregate-only?
6. Do Health Connect background reads work?
7. Real watch → Health Connect latency, per type.
8. Real MB/day and battery cost of continuous raw capture.
9. Do the developer modes survive reboots and app updates?
10. Which Health Connect types outside Samsung's mapping actually carry data?

Only then does the lake get designed — against measured volumes, not estimates.

---

## A note on the data

Everything this repo touches is personal health data. `dumps/`, `export-raw/`, `*.jsonl` and
`*.parquet` are gitignored. Keep it that way.
