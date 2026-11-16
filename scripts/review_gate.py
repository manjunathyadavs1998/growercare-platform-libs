#!/usr/bin/env python3
"""SPEC-PLAT-03 R45-R48: turn Claude's structured review findings into a deterministic gate.

Input : env CLAUDE_REVIEW_JSON (the action's `structured_output`), or --file path.
Output: Markdown summary (stdout or --summary-file), GitHub annotations, labels to set (--labels-file),
        exit 1 when any Critical/High finding exists OR the output is malformed (fail closed, R48).
Self-test: `python scripts/review_gate.py --selftest` runs spec examples PLAT-E50..E55.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass

SEVERITIES = ("Critical", "High", "Medium", "Low")
BLOCKING = {"Critical", "High"}
ICON = {"Critical": "🔴", "High": "🟠", "Medium": "🟡", "Low": "🔵"}


@dataclass(frozen=True)
class Finding:
    severity: str
    file: str
    line: int | None
    title: str
    detail: str
    rule: str


@dataclass(frozen=True)
class Outcome:
    passed: bool
    labels: tuple[str, ...]
    summary: str
    findings: tuple[Finding, ...]
    error: str | None = None


def parse(raw: str | None) -> list[Finding]:
    """Strict parse; any schema violation raises ValueError (fail closed)."""
    if not raw or not raw.strip():
        raise ValueError("empty structured output")
    data = json.loads(raw)
    if not isinstance(data, dict) or not isinstance(data.get("findings"), list):
        raise ValueError("structured output has no 'findings' array")
    findings = []
    for i, f in enumerate(data["findings"]):
        if not isinstance(f, dict):
            raise ValueError(f"finding {i} is not an object")
        sev = f.get("severity")
        if sev not in SEVERITIES:
            raise ValueError(f"finding {i} has invalid severity {sev!r}")
        title = str(f.get("title", "")).strip()
        if not title:
            raise ValueError(f"finding {i} has no title")
        line = f.get("line")
        findings.append(Finding(sev, str(f.get("file", "")).strip(), int(line) if isinstance(line, int) and line > 0 else None,
                                title, str(f.get("detail", "")).strip(), str(f.get("rule", "")).strip()))
    return findings


def evaluate(raw: str | None) -> Outcome:
    try:
        findings = parse(raw)
    except (ValueError, json.JSONDecodeError) as e:
        msg = f"review incomplete: {e}"
        return Outcome(False, ("review:incomplete",), f"### Claude review\n\n❌ **{msg}** — the check fails closed (SPEC-PLAT-03 R48).", (), msg)

    counts = {s: sum(1 for f in findings if f.severity == s) for s in SEVERITIES}
    labels = tuple(f"review:{s.lower()}" for s in SEVERITIES if counts[s]) or ("review:clean",)
    blocking = sum(counts[s] for s in BLOCKING)
    passed = blocking == 0

    lines = ["### Claude review — severity gate", ""]
    lines.append("| Severity | Count |")
    lines.append("|---|---|")
    lines += [f"| {ICON[s]} {s} | {counts[s]} |" for s in SEVERITIES]
    lines.append("")
    lines.append("✅ **No Critical/High findings — gate passed.**" if passed
                 else f"❌ **{blocking} Critical/High finding(s) — merge blocked until fixed** (SPEC-PLAT-03 R46).")
    if findings:
        lines += ["", "| # | Severity | Location | Finding | Rule |", "|---|---|---|---|---|"]
        order = sorted(findings, key=lambda f: SEVERITIES.index(f.severity))
        for n, f in enumerate(order, 1):
            loc = f"`{f.file}:{f.line}`" if f.file and f.line else (f"`{f.file}`" if f.file else "—")
            lines.append(f"| {n} | {ICON[f.severity]} {f.severity} | {loc} | **{_md(f.title)}** — {_md(f.detail)} | {_md(f.rule) or '—'} |")
    return Outcome(passed, labels, "\n".join(lines), tuple(findings))


def _md(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def annotations(outcome: Outcome) -> list[str]:
    out = []
    if outcome.error:
        out.append(f"::error title=Claude review incomplete::{outcome.error}")
    for f in outcome.findings:
        level = "error" if f.severity in BLOCKING else "warning" if f.severity == "Medium" else "notice"
        where = f"file={f.file},line={f.line}," if f.file and f.line else (f"file={f.file}," if f.file else "")
        out.append(f"::{level} {where}title={f.severity}: {f.title[:120]}::{f.detail[:900]}")
    return out


# ------------------------------------------------------------------------------------------------ self-test

def selftest() -> int:
    def finding(sev):  # minimal valid finding
        return {"severity": sev, "file": "gc-backend/X.java", "line": 3, "title": f"{sev} issue", "detail": "d", "rule": "r"}

    cases = [
        ("PLAT-E50 no findings", json.dumps({"findings": []}), True, ("review:clean",)),
        ("PLAT-E51 medium+low only", json.dumps({"findings": [finding("Low"), finding("Medium"), finding("Medium")]}), True, ("review:medium", "review:low")),
        ("PLAT-E52 one high", json.dumps({"findings": [finding("High")]}), False, ("review:high",)),
        ("PLAT-E53 critical+low", json.dumps({"findings": [finding("Critical"), finding("Low")]}), False, ("review:critical", "review:low")),
        ("PLAT-E54a empty output", "", False, ("review:incomplete",)),
        ("PLAT-E54b not json", "not json", False, ("review:incomplete",)),
        ("PLAT-E54c no findings key", json.dumps({"summary": "ok"}), False, ("review:incomplete",)),
        ("PLAT-E55 unknown severity", json.dumps({"findings": [finding("Blocker")]}), False, ("review:incomplete",)),
    ]
    failed = 0
    for name, raw, expected_pass, expected_labels in cases:
        o = evaluate(raw)
        ok = o.passed == expected_pass and o.labels == expected_labels
        failed += not ok
        print(("ok   " if ok else "FAIL ") + f"{name}: passed={o.passed} labels={o.labels}")
    print(f"review_gate self-test: {len(cases) - failed} passed, {failed} failed")
    return 1 if failed else 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")   # Windows consoles default to cp1252
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--file")
    ap.add_argument("--summary-file")
    ap.add_argument("--labels-file")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    raw = open(args.file, encoding="utf-8").read() if args.file else os.environ.get("CLAUDE_REVIEW_JSON")
    outcome = evaluate(raw)
    for a in annotations(outcome):
        print(a)
    if args.summary_file:
        with open(args.summary_file, "w", encoding="utf-8") as fh:
            fh.write(outcome.summary + "\n")
    else:
        print(outcome.summary)
    if args.labels_file:
        with open(args.labels_file, "w", encoding="utf-8") as fh:
            fh.write("\n".join(outcome.labels) + "\n")
    return 0 if outcome.passed else 1


if __name__ == "__main__":
    sys.exit(main())
