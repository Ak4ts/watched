#!/usr/bin/env python3
"""Turn probe dumps into the tables that go in docs/findings.md.

Standard library only, so there is no venv to set up.

    python3 tools/analyze_dumps.py ./dumps [./dumps-watch ...]

Reads each run directory (one per probe run), summarises run-report.json, and measures the
.jsonl dumps directly so the numbers do not depend on the probe having reported them correctly.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path


def human_bytes(n: float) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(n) < 1024:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TiB"


def iso(ms: int | None) -> str:
    if not ms:
        return "—"
    import datetime as dt

    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).strftime("%Y-%m-%d %H:%M")


def measure_jsonl(path: Path) -> dict:
    """Independently measure a dump file: count, bytes, and the real payload key set."""
    count = 0
    keys: dict[str, int] = {}
    captured: list[int] = []
    for line in path.open(encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            continue
        count += 1
        if isinstance(rec.get("capturedAt"), int):
            captured.append(rec["capturedAt"])
        payload = rec.get("payload")
        if isinstance(payload, dict):
            for k, v in payload.items():
                if v is not None:
                    keys[k] = keys.get(k, 0) + 1
    return {
        "count": count,
        "bytes": path.stat().st_size,
        "keys": keys,
        "capturedSpanMs": (max(captured) - min(captured)) if len(captured) > 1 else None,
    }


def analyse_run(run_dir: Path) -> None:
    print(f"\n## Run `{run_dir.name}`\n")

    report_path = run_dir / "run-report.json"
    report = {}
    if report_path.exists():
        report = json.loads(report_path.read_text(encoding="utf-8"))
        device = report.get("device", {})
        if device:
            print("| Property | Value |")
            print("|---|---|")
            for k, v in device.items():
                print(f"| {k} | `{v}` |")
            print()
    else:
        print("_No run-report.json — press the 'Write report' button before pulling._\n")

    dumps = sorted(p for p in run_dir.glob("*.jsonl"))
    if not dumps:
        print("_No .jsonl dumps in this run._\n")
    else:
        print("| Surface | Type | Records | Bytes | Span | Projected/day | Fields seen |")
        print("|---|---|---:|---:|---|---:|---|")
        for path in dumps:
            surface, _, type_name = path.stem.partition(".")
            m = measure_jsonl(path)
            span = m["capturedSpanMs"]
            if span and span > 0:
                per_day = m["bytes"] * 86_400_000 / span
                span_s = f"{span / 1000:.0f}s"
                proj = human_bytes(per_day)
            else:
                span_s, proj = "—", "—"
            field_list = ", ".join(sorted(m["keys"])[:8]) or "—"
            if len(m["keys"]) > 8:
                field_list += f", +{len(m['keys']) - 8} more"
            print(
                f"| {surface} | `{type_name}` | {m['count']} | {human_bytes(m['bytes'])} "
                f"| {span_s} | {proj} | {field_list} |"
            )
        print()

    reports = report.get("reports", [])
    if reports:
        print("### Probe-reported results\n")
        print("| Surface | Type | Avail | Perm | Count | Oldest | Newest | Median gap | Error |")
        print("|---|---|:-:|:-:|---:|---|---|---:|---|")
        for r in reports:
            gap = r.get("medianIntervalMs")
            gap_s = f"{gap} ms" if gap is not None else "—"
            err = (r.get("error") or "").replace("|", "\\|")[:60] or "—"
            print(
                f"| {r.get('surface')} | `{r.get('type')}` "
                f"| {'yes' if r.get('available') else 'NO'} "
                f"| {'yes' if r.get('permissionGranted') else 'NO'} "
                f"| {r.get('recordCount', 0)} | {iso(r.get('oldestRecord'))} "
                f"| {iso(r.get('newestRecord'))} | {gap_s} | {err} |"
            )
        print()

        notable = [r for r in reports if r.get("notes")]
        if notable:
            print("### Notes\n")
            for r in notable:
                for note in r["notes"]:
                    print(f"- `{r.get('type')}`: {note}")
            print()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("dumps", nargs="+", type=Path, help="dump root(s) pulled from a device")
    args = ap.parse_args()

    for root in args.dumps:
        if not root.is_dir():
            print(f"skip: {root} is not a directory", file=sys.stderr)
            continue
        # A dump root holds run directories; tolerate being pointed at a single run too.
        runs = sorted(p for p in root.iterdir() if p.is_dir())
        if not runs and any(root.glob("*.jsonl")):
            runs = [root]
        print(f"\n# {root}")
        for run in runs:
            analyse_run(run)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
