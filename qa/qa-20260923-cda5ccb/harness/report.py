"""Prompt 14/15: build the coverage ledger, the results table, the bug list and the release verdict
from results.csv. Rows whose test_id ends in 'b' or '-NOTE' supersede the row they name; superseded
rows are kept in the ledger (never erased) and excluded from the counts."""

import collections
import csv
import datetime as dt
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
rows = list(csv.DictReader(open(ROOT / "results.csv")))

# supersession: a row "X-b"/"Xb"/"X-NOTE" supersedes "X"; explicit mentions "Superseded by Y" in actual text also count
superseded = set()
ids = {r["test_id"] for r in rows}
for r in rows:
    t = r["test_id"]
    for base in (t[:-1] if t.endswith("b") else None, t[:-2] if t.endswith("-b") else None, t.replace("-NOTE", "") if t.endswith("-NOTE") else None):
        if base and base in ids and base != t:
            superseded.add(base)
    m = re.search(r"P3-VAL-08\.\.11", r["source_requirement"])
    if m:
        superseded |= {"P3-VAL-08", "P3-VAL-09", "P3-VAL-10", "P3-VAL-11"}
    if "P6-S13 / ML-01 / ML-03 / ML-04" in r["source_requirement"]:
        superseded |= {"P6-S13", "P6-ML-01", "P6-ML-03", "P6-ML-04"}
    if r["test_id"] == "P1-FAIL-06-NOTE":
        superseded.add("P1-FAIL-06")
    if r["test_id"] == "P2-TOK-03-NOTE":
        superseded.add("P2-TOK-03")
    if r["test_id"] == "P5-POL-02-NOTE":
        superseded.add("P5-POL-02")
    if r["test_id"] == "P1-FAIL-01c":
        superseded.add("P1-FAIL-01b")
    if r["test_id"] == "P5-SUP-04b":
        superseded.add("P5-SUP-04")
    if r["test_id"] == "P5-SUP-05b":
        superseded.add("P5-SUP-05")
    if r["test_id"] == "P6-S15b":
        superseded.add("P6-S15")
    if r["test_id"] == "P1-PERSONA-01b":
        superseded.add("P1-PERSONA-01")
    if r["test_id"] == "P4-R03b":
        superseded.add("P4-R03")
    if r["test_id"] == "P6-S12b":
        superseded.add("P6-S12")
    if r["test_id"] == "P5-MONEY-ZERO-b":
        superseded.add("P5-MONEY-ZERO")
    if r["test_id"] == "P5-MONEY-WRONG-b":
        superseded.add("P5-MONEY-WRONG")
    if r["test_id"] == "P11-AUTHZ-01b":
        superseded.add("P11-AUTHZ-01")
    if r["test_id"] == "P11-SECRETS-01b":
        superseded.add("P11-SECRETS-01")
    if r["test_id"] == "P3-LIFE-01b":
        superseded.add("P3-LIFE-01")
    if r["test_id"] in ("P3-VAL-14b", "P3-ITN-03b", "P3-MUT-02b", "P3-LIST-02b"):
        superseded.add(r["test_id"][:-1])
# duplicate test ids (a placeholder row followed by the real one): keep the last
last = {}
for r in rows:
    last[r["test_id"]] = r
effective = [r for t, r in last.items() if t not in superseded and not t.endswith("-NOTE")]

def prompt_of(t):
    m = re.match(r"P(\d+)-", t)
    return int(m.group(1)) if m else 0

counts = collections.Counter(r["status"] for r in effective)
by_prompt = collections.defaultdict(collections.Counter)
by_prio = collections.defaultdict(collections.Counter)
by_layer = collections.defaultdict(collections.Counter)
for r in effective:
    by_prompt[prompt_of(r["test_id"])][r["status"]] += 1
    by_prio[r["priority"] or "—"][r["status"]] += 1
    layer = r["test_layer"].split("(")[0].split("+")[0].strip().lower()
    layer = "browser" if "browser" in layer else ("api" if layer.startswith("api") else ("inventory" if "inventory" in layer or "cross-reference" in layer else layer[:24]))
    by_layer[layer][r["status"]] += 1

bugs = collections.defaultdict(list)
for r in effective:
    for b in re.findall(r"(BUG-\d+|RISK-\d+)", r["bug_id"]):
        bugs[b].append(r["test_id"])

STATUSES = ["PASS", "FAIL", "BLOCKED", "NOT_IMPLEMENTED", "NOT_APPLICABLE"]

def table(d, label):
    out = [f"| {label} | " + " | ".join(STATUSES) + " | total |", "|---|" + "---|" * (len(STATUSES) + 1)]
    for k in sorted(d, key=lambda x: (str(type(x)), x)):
        c = d[k]
        out.append(f"| {k} | " + " | ".join(str(c.get(s, 0)) for s in STATUSES) + f" | {sum(c.values())} |")
    return "\n".join(out)

executed = counts["PASS"] + counts["FAIL"]
md = []
md.append(f"# Results summary — {ROOT.name}\n")
md.append(f"Generated {dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')} from results.csv ({len(rows)} rows, {len(effective)} effective after supersession, {len(superseded)} superseded rows kept for the record).\n")
md.append("## Totals\n")
md.append("| " + " | ".join(STATUSES) + " | total | executed | executed pass rate |")
md.append("|---|" * (len(STATUSES) + 3))
md.append("| " + " | ".join(str(counts.get(s, 0)) for s in STATUSES) + f" | {len(effective)} | {executed} | {100 * counts['PASS'] / executed:.0f}% |\n")
md.append("## By prompt\n" + table(by_prompt, "prompt") + "\n")
md.append("## By priority\n" + table(by_prio, "priority") + "\n")
md.append("## By test layer\n" + table(by_layer, "layer") + "\n")
md.append("## Bugs and risks referenced\n")
for b in sorted(bugs, key=lambda x: (x.split("-")[0], int(x.split("-")[1]))):
    md.append(f"- {b}: {', '.join(sorted(set(bugs[b])))}")
md.append("\n## Failed tests (effective)\n")
md.append("| test | priority | actual |\n|---|---|---|")
for r in sorted(effective, key=lambda r: (r["priority"], r["test_id"])):
    if r["status"] == "FAIL":
        md.append(f"| {r['test_id']} | {r['priority']} | {r['actual'][:220].replace('|', '/')} |")
md.append("\n## Not implemented / blocked / not applicable (effective)\n")
md.append("| test | status | why |\n|---|---|---|")
for r in sorted(effective, key=lambda r: r["test_id"]):
    if r["status"] in ("NOT_IMPLEMENTED", "BLOCKED", "NOT_APPLICABLE"):
        md.append(f"| {r['test_id']} | {r['status']} | {r['actual'][:200].replace('|', '/')} |")
(ROOT / "results-summary.md").write_text("\n".join(md) + "\n")
json.dump({"counts": dict(counts), "effective": len(effective), "superseded": sorted(superseded), "bugs": {k: sorted(set(v)) for k, v in bugs.items()}}, open(ROOT / "results-summary.json", "w"), indent=1)
print(json.dumps({"counts": dict(counts), "effective": len(effective), "executed_pass_rate": round(100 * counts["PASS"] / executed, 1) if executed else None}))
