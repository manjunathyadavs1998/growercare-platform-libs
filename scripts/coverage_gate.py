#!/usr/bin/env python3
"""SPEC-PLAT-03 R41/R42: overall line-coverage gate on the merged JaCoCo report.

  python scripts/coverage_gate.py <jacoco.xml> \
         [--min-line 80] [--summary-file out.md]
Exit 1 when overall LINE coverage < threshold or the report is missing (fail closed).
Self-test: `python scripts/coverage_gate.py --selftest` (spec examples PLAT-E56..E58).
"""
from __future__ import annotations

import argparse
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

MARKER = "<!-- coverage-gate -->"


def counters(element) -> dict[str, tuple[int, int]]:
    """Direct child <counter> elements → {type: (missed, covered)}."""
    return {c.get("type"): (int(c.get("missed")), int(c.get("covered"))) for c in element.findall("counter")}


def pct(c: dict, kind: str) -> float | None:
    missed, covered = c.get(kind, (0, 0))
    total = missed + covered
    return None if total == 0 else 100.0 * covered / total


def evaluate(xml_path: str, min_line: float) -> tuple[bool, str]:
    if not os.path.isfile(xml_path):
        return False, f"### Coverage gate\n\n❌ **no coverage report** at `{xml_path}` (SPEC-PLAT-03 R40)."
    root = ET.parse(xml_path).getroot()
    overall = counters(root)
    line = pct(overall, "LINE") or 0.0
    branch = pct(overall, "BRANCH")
    passed = round(line, 1) >= min_line

    rows = []
    for group in root.findall("group"):                     # report-aggregate: one <group> per module
        c = counters(group)
        rows.append((group.get("name"), pct(c, "LINE"), pct(c, "BRANCH")))
    fmt = lambda v: "—" if v is None else f"{v:.1f}%"
    out = [MARKER, "### Coverage gate (merged unit + integration, all modules)", "",
           f"**Line {fmt(line)}** (threshold {min_line:.0f}%) · Branch {fmt(branch)} — "
           + ("✅ passed" if passed else "❌ **below threshold: build fails** (SPEC-PLAT-03 R41)"), ""]
    if rows:
        out += ["| Module | Line | Branch |", "|---|---|---|"]
        out += [f"| {name} | {fmt(l)} | {fmt(b)} |" for name, l, b in sorted(rows)]
    return passed, "\n".join(out)


def selftest() -> int:
    def report(missed, covered):
        return (f'<report name="x"><group name="m"><counter type="LINE" missed="{missed}" covered="{covered}"/></group>'
                f'<counter type="LINE" missed="{missed}" covered="{covered}"/>'
                f'<counter type="BRANCH" missed="1" covered="1"/></report>')
    cases = [("PLAT-E56 exactly 80.0%", report(200, 800), True),
             ("PLAT-E57 79.9%", report(201, 799), False),
             ("PLAT-E58 report missing", None, False)]
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, xml, expected in cases:
            path = os.path.join(tmp, name.split()[0] + ".xml")
            if xml is not None:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(xml)
            ok_, _ = evaluate(path, 80.0)
            ok = ok_ == expected
            failed += not ok
            print(("ok   " if ok else "FAIL ") + f"{name}: passed={ok_}")
    print(f"coverage_gate self-test: {len(cases) - failed} passed, {failed} failed")
    return 1 if failed else 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")   # Windows consoles default to cp1252
    ap = argparse.ArgumentParser()
    ap.add_argument("report", nargs="?")
    ap.add_argument("--min-line", type=float, default=80.0)
    ap.add_argument("--summary-file")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    passed, summary = evaluate(args.report, args.min_line)
    if args.summary_file:
        with open(args.summary_file, "w", encoding="utf-8") as fh:
            fh.write(summary + "\n")
    print(summary)
    if not passed:
        print(f"::error title=Coverage below {args.min_line:.0f}%::see the coverage summary for per-module numbers")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
