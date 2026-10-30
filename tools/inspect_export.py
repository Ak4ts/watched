#!/usr/bin/env python3
"""Inventory a Samsung Health 'Download personal data' export.

Settles the Surface D questions in docs/capability-matrix.md section 5: which files exist, what
their real headers are, and whether stress / HRV sdnn+rmssd / full BIA are actually in there.

    python3 tools/inspect_export.py ./export-raw

Standard library only. Pass the export path as an argument -- never run this from inside the
export directory, which is untrusted device data.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

# The claims we are here to confirm or kill.
INTERESTING = {
    "stress": "stress — claimed but no filename found in any source",
    "sdnn": "HRV SDNN — disputed",
    "rmssd": "HRV RMSSD — disputed",
    "skeletal_muscle": "BIA skeletal muscle — disputed",
    "total_body_water": "BIA body water — disputed",
    "body_fat_mass": "BIA fat mass — disputed",
    "vfa_level": "BIA visceral fat — disputed",
    "basal_metabolic_rate": "BIA BMR — disputed",
    "sleep_score": "sleep score — unknown",
    "skin_temperature": "skin temperature — unknown",
    "spo2": "SpO2 (as `spo2`)",
    "oxygen_saturation": "SpO2 (as `oxygen_saturation`)",
}


def sniff_csv(path: Path) -> dict:
    """Read a Samsung export CSV, tolerating the non-header preamble line."""
    preamble = None
    header: list[str] = []
    rows = 0
    try:
        with path.open(encoding="utf-8-sig", errors="replace", newline="") as fh:
            reader = csv.reader(fh)
            first = next(reader, None)
            if first is None:
                return {"header": [], "rows": 0, "preamble": None}
            # Samsung prefixes some files with e.g. `com.samsung.health.heart_rate,7006003,6`.
            # Heuristic: a real header has a start_time-ish column; a preamble does not.
            if not any(c.strip() in ("start_time", "create_time", "day_time") for c in first):
                second = next(reader, None)
                if second is not None:
                    preamble, header = first, second
                else:
                    header = first
            else:
                header = first
            for _ in reader:
                rows += 1
    except Exception as exc:  # noqa: BLE001 - diagnostics, report and continue
        return {"header": [], "rows": 0, "preamble": None, "error": f"{type(exc).__name__}: {exc}"}
    return {"header": [c.strip() for c in header], "rows": rows, "preamble": preamble}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("export", type=Path, help="path to the pulled export directory")
    ap.add_argument("--full", action="store_true", help="print every column, not just the first 12")
    args = ap.parse_args()

    root: Path = args.export
    if not root.is_dir():
        print(f"error: {root} is not a directory", file=sys.stderr)
        return 1

    csvs = sorted(root.rglob("*.csv"))
    jsons = sorted(root.rglob("*.json"))

    print(f"# Samsung Health export inventory: {root}\n")
    print(f"- CSV files: {len(csvs)}")
    print(f"- JSON files: {len(jsons)}")
    total = sum(p.stat().st_size for p in root.rglob("*") if p.is_file())
    print(f"- Total size: {total / 1024 / 1024:.1f} MiB\n")

    print("## CSV files\n")
    print("| File | Rows | Preamble? | Columns |")
    print("|---|---:|:-:|---|")
    all_columns: Counter[str] = Counter()
    for path in csvs:
        info = sniff_csv(path)
        cols = info["header"]
        all_columns.update(c.lower() for c in cols)
        shown = cols if args.full else cols[:12]
        col_s = ", ".join(f"`{c}`" for c in shown)
        if not args.full and len(cols) > 12:
            col_s += f", +{len(cols) - 12} more"
        err = info.get("error")
        print(
            f"| `{path.relative_to(root)}` | {info['rows']} "
            f"| {'yes' if info['preamble'] else 'no'} | {err or col_s or '—'} |"
        )

    print("\n## Disputed claims\n")
    print("| Looking for | Found? | Where |")
    print("|---|:-:|---|")
    for needle, why in INTERESTING.items():
        hits_f = [p.name for p in csvs if needle in p.name.lower()]
        hits_c = [c for c in all_columns if needle in c]
        found = bool(hits_f or hits_c)
        where = ""
        if hits_f:
            where += "files: " + ", ".join(f"`{h}`" for h in hits_f[:3])
        if hits_c:
            where += (" / " if where else "") + "columns: " + ", ".join(f"`{h}`" for h in hits_c[:3])
        print(f"| {why} | {'**YES**' if found else 'no'} | {where or '—'} |")

    if jsons:
        print("\n## JSON blobs (sample)\n")
        for path in jsons[:5]:
            try:
                data = json.loads(path.read_text(encoding="utf-8", errors="replace"))
            except Exception as exc:  # noqa: BLE001
                print(f"- `{path.relative_to(root)}`: unparseable ({type(exc).__name__})")
                continue
            if isinstance(data, list):
                shape = f"array[{len(data)}]"
                keys = sorted(data[0].keys()) if data and isinstance(data[0], dict) else []
            elif isinstance(data, dict):
                shape = "object"
                keys = sorted(data.keys())
            else:
                shape, keys = type(data).__name__, []
            print(f"- `{path.relative_to(root)}`: {shape}, keys: {', '.join(keys[:12]) or '—'}")
        if len(jsons) > 5:
            print(f"- ... and {len(jsons) - 5} more")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
