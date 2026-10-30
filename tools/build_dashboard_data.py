#!/usr/bin/env python3
"""Condense the Samsung Data SDK dumps into one JSON the dashboard can load.

    python3 tools/build_dashboard_data.py dumps/20261030-034132 dashboards/data.json

Standard library only. The dumps are hourly buckets whose real resolution lives in
`binning_data`; this flattens that out rather than reporting record counts, which badly
understate the data.
"""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import json
import os
import statistics
from pathlib import Path


def iso(s):
    if not isinstance(s, str):
        return None
    try:
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None


def load(run: Path, name: str) -> list[dict]:
    p = run / f"samsung_data_sdk.{name}.jsonl"
    if not p.exists():
        return []
    out = []
    with p.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line)["payload"])
    return out


def daily_from_binning(records, value_key):
    """Collapse per-minute binning entries into per-day min/mean/max + sample count."""
    buckets = collections.defaultdict(list)
    for r in records:
        for b in r.get("binning_data") or []:
            t = iso(b.get("startTime"))
            v = b.get(value_key)
            if t and isinstance(v, (int, float)):
                buckets[t.date().isoformat()].append(v)
    return [
        {
            "date": d,
            "min": round(min(vs), 2),
            "mean": round(statistics.fmean(vs), 2),
            "max": round(max(vs), 2),
            "n": len(vs),
        }
        for d, vs in sorted(buckets.items())
    ]


def minute_series(records, value_key, cap=20000):
    """Flat per-minute series, for the high-resolution detail view."""
    pts = []
    for r in records:
        for b in r.get("binning_data") or []:
            t = iso(b.get("startTime"))
            v = b.get(value_key)
            if t and isinstance(v, (int, float)):
                pts.append([int(t.timestamp()), round(v, 2)])
    pts.sort()
    return pts[:cap]


def parse_iso_duration(s):
    """PT6H6M -> minutes. Only the shapes Samsung actually emits."""
    if not isinstance(s, str) or not s.startswith("PT"):
        return None
    num, mins = "", 0
    for ch in s[2:]:
        if ch.isdigit():
            num += ch
        elif ch == "H":
            mins += int(num or 0) * 60
            num = ""
        elif ch == "M":
            mins += int(num or 0)
            num = ""
        elif ch == "S":
            num = ""
    return mins


def build(run: Path) -> dict:
    hr = load(run, "heart_rate")
    spo2 = load(run, "blood_oxygen")
    skin = load(run, "skin_temperature")
    energy = load(run, "energy_score")
    sleep = load(run, "sleep")
    exercise = load(run, "exercise")
    floors = load(run, "floors_climbed")
    profile = load(run, "user_profile")

    # Sleep: one row per night, stage minutes + score.
    nights = []
    stage_names = set()
    for r in sleep:
        for s in r.get("sessions") or []:
            start = iso(s.get("startTime")) or iso(r.get("_startTime"))
            if not start:
                continue
            stages = collections.Counter()
            for st in s.get("stages") or []:
                a, b = iso(st.get("startTime")), iso(st.get("endTime"))
                nm = st.get("stage")
                if a and b and nm:
                    stages[nm] += (b - a).total_seconds() / 60
                    stage_names.add(nm)
            nights.append({
                "date": start.date().isoformat(),
                "start": start.isoformat(),
                "score": r.get("sleep_score"),
                "durationMin": parse_iso_duration(s.get("duration") or r.get("sleep_duration")),
                "stages": {k: round(v, 1) for k, v in stages.items()},
            })
    nights.sort(key=lambda n: n["date"])

    # Exercise: aggregate by type, and keep sessions for the detail table.
    by_type = collections.Counter()
    ex_minutes = collections.Counter()
    ex_calories = collections.Counter()
    for r in exercise:
        t = r.get("exercise_type") or "UNKNOWN"
        by_type[t] += 1
        for s in r.get("sessions") or []:
            a, b = iso(s.get("startTime")) or iso(r.get("_startTime")), iso(s.get("endTime")) or iso(r.get("_endTime"))
            if a and b:
                ex_minutes[t] += (b - a).total_seconds() / 60
            c = s.get("calories")
            if isinstance(c, (int, float)):
                ex_calories[t] += c

    floors_daily = collections.Counter()
    for r in floors:
        t = iso(r.get("_startTime"))
        v = r.get("floor")
        if t and isinstance(v, (int, float)):
            floors_daily[t.date().isoformat()] += v

    energy_daily = []
    for r in energy:
        t = iso(r.get("_startTime"))
        v = r.get("total_score")
        if t and isinstance(v, (int, float)):
            energy_daily.append({"date": t.date().isoformat(), "score": round(v, 1)})
    energy_daily.sort(key=lambda e: e["date"])

    hr_daily = daily_from_binning(hr, "heartRate")
    spo2_daily = daily_from_binning(spo2, "oxygenSaturation")
    skin_daily = daily_from_binning(skin, "skinTemperature")

    # Coverage: per-minute samples per day, across every binned type. This is what makes the
    # intermittent wear honest instead of hiding it behind record counts.
    coverage = collections.Counter()
    for recs, key in ((hr, "heartRate"), (spo2, "oxygenSaturation"), (skin, "skinTemperature")):
        for r in recs:
            for b in r.get("binning_data") or []:
                t = iso(b.get("startTime"))
                if t:
                    coverage[t.date().isoformat()] += 1

    total_binned = sum(coverage.values())
    all_dates = sorted(coverage) or [n["date"] for n in nights]

    return {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "run": run.name,
        "profile": profile[0] if profile else {},
        "totals": {
            "records": len(hr) + len(spo2) + len(skin) + len(energy) + len(sleep) + len(exercise) + len(floors),
            "hrRecords": len(hr),
            "binnedSamples": total_binned,
            "nights": len(nights),
            "workouts": len(exercise),
            "firstDate": all_dates[0] if all_dates else None,
            "lastDate": all_dates[-1] if all_dates else None,
            "daysWithData": len(coverage),
        },
        "heartRate": {"daily": hr_daily, "minutes": minute_series(hr, "heartRate")},
        "spo2": {"daily": spo2_daily},
        "skinTemp": {"daily": skin_daily},
        "energy": energy_daily,
        "sleep": {"nights": nights, "stageNames": sorted(stage_names)},
        "exercise": {
            "byType": [
                {"type": t, "sessions": c,
                 "minutes": round(ex_minutes[t], 1),
                 "calories": round(ex_calories[t], 1)}
                for t, c in by_type.most_common()
            ]
        },
        "floors": [{"date": d, "floors": round(v, 1)} for d, v in sorted(floors_daily.items())],
        "coverage": [{"date": d, "samples": n} for d, n in sorted(coverage.items())],
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("run", type=Path)
    ap.add_argument("out", type=Path)
    a = ap.parse_args()
    data = build(a.run)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
    t = data["totals"]
    print(f"wrote {a.out}  ({a.out.stat().st_size/1024:.0f} KiB)")
    print(f"  {t['firstDate']} -> {t['lastDate']}  |  {t['daysWithData']} days with data")
    print(f"  {t['binnedSamples']:,} per-minute samples, {t['nights']} nights, {t['workouts']} workouts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
