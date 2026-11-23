"""Rules engine shared in spirit with the phone.

The ruleset is plain JSON (ruleset.json) so the Android app downloads the same file in its
offline pack and runs the same evaluation in Kotlin (RulesEngine.kt) for the PROVISIONAL
result. The server run is the FINAL result.

Principles enforced here (design 1.3 / 8.5):
  * screens, never diagnoses: GREEN / YELLOW / RED (+ INCOMPLETE, treated as at least yellow)
  * rules decide, AI may only raise risk, the worse result wins
  * "I am worried" -> at least YELLOW
  * missing required input -> INCOMPLETE, never GREEN
  * child result = worst D
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any

GREEN, YELLOW, INCOMPLETE, RED = "GREEN", "YELLOW", "INCOMPLETE", "RED"
_RANK = {GREEN: 0, YELLOW: 1, INCOMPLETE: 1, RED: 2}
DOMAINS = ("D1", "D2", "D3", "D4")


def worse(a: str, b: str) -> str:
    if _RANK[b] > _RANK[a]:
        return b
    if _RANK[b] == _RANK[a] and b == INCOMPLETE:  # surface "incomplete" so a retake task is made
        return b
    return a


@lru_cache
def load_ruleset() -> dict:
    return json.loads((Path(__file__).parent / "ruleset.json").read_text(encoding="utf-8"))


def _in_age(item: dict, age_months: int) -> bool:
    lo, hi = item.get("age_months", [0, 216])
    return lo <= age_months <= hi


def _derived(ruleset: dict, age_months: int) -> dict:
    out = {}
    for name, steps in ruleset.get("derived", {}).items():
        val = steps[0][1]
        for min_age, v in steps:
            if age_months >= min_age:
                val = v
        out[name] = val
    return out


def _lookup(ctx: dict, path: str) -> Any:
    cur: Any = ctx
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur or cur[part] is None:
            return None
        cur = cur[part]
    return cur


def eval_condition(cond: dict, ctx: dict) -> bool:
    if "all" in cond:
        return all(eval_condition(c, ctx) for c in cond["all"])
    if "any" in cond:
        return any(eval_condition(c, ctx) for c in cond["any"])
    left = _lookup(ctx, cond["var"])
    if left is None:
        return False
    if "value_var" in cond:
        right = _lookup(ctx, cond["value_var"])
        if right is None:
            return False
        right = right * cond.get("factor", 1)
    else:
        right = cond["value"]
    op = cond["op"]
    try:
        if op == "<":
            return left < right
        if op == "<=":
            return left <= right
        if op == ">":
            return left > right
        if op == ">=":
            return left >= right
        if op == "==":
            return left == right
        if op == "!=":
            return left != right
        if op == "in":
            return left in right
    except TypeError:
        return False
    raise ValueError(f"Unknown op {op}")


@dataclass
class Evidence:
    rule_id: str
    color: str
    text_hi: str
    text_en: str
    source: str  # rule | question | worker | ai | missing | skipped
    refer: str | None = None
    focus: str | None = None
    urgency: str | None = None
    family: str | None = None  # rules on the same variable keep only the most severe hit

    def as_dict(self) -> dict:
        return {k: v for k, v in self.__dict__.items() if v is not None and k != "family"}


@dataclass
class DomainInput:
    domain: str
    answers: dict = field(default_factory=dict)
    readings: dict = field(default_factory=dict)
    tasks: dict = field(default_factory=dict)
    worried: bool = False
    worried_reason: str | None = None
    skipped_reason: str | None = None
    ai_findings: list = field(default_factory=list)  # [{model, sign, present: bool, color, evidence_ids:[..]}]


@dataclass
class DomainOutcome:
    domain: str
    color: str
    evidence: list[Evidence]


def questions_for(domain: str, age_months: int) -> list[dict]:
    return [q for q in load_ruleset()["questions"] if q["domain"] == domain and _in_age(q, age_months)]


def evaluate_domain(inp: DomainInput, age_months: int, ruleset: dict | None = None) -> DomainOutcome:
    rs = ruleset or load_ruleset()
    d = inp.domain
    ctx = {"answers": inp.answers, "readings": inp.readings, "tasks": inp.tasks,
           "derived": _derived(rs, age_months)}
    color = GREEN
    ev: list[Evidence] = []

    if inp.skipped_reason:
        ev.append(Evidence("SKIP", INCOMPLETE, f"छोड़ा गया: {inp.skipped_reason}", f"Skipped: {inp.skipped_reason}", "skipped"))
        color = worse(color, INCOMPLETE)
    else:
        # 1) required inputs
        for req in rs.get("required", []):
            if req["domain"] == d and _in_age(req, age_months):
                for var in req["vars"]:
                    if _lookup(ctx, var) is None:
                        ev.append(Evidence("REQ-" + var, INCOMPLETE, "ज़रूरी जाँच छूटी - दोबारा लें",
                                           f"Missing required input {var}", "missing"))
                        color = worse(color, INCOMPLETE)

        # 2) questionnaire
        for q in questions_for(d, age_months):
            a = inp.answers.get(q["id"])
            if a is None:
                continue
            if a == q["risk_on"]:
                ev.append(Evidence(q["id"], q["color"], q["text_hi"], q["text_en"], "question", q.get("refer")))
                color = worse(color, q["color"])
            elif a == "unsure" and q.get("flag_unsure", q["color"] == RED):
                ev.append(Evidence(q["id"], YELLOW, "पक्का नहीं - " + q["text_hi"], "Unsure - " + q["text_en"], "question"))
                color = worse(color, YELLOW)

        # 3) threshold rules (first match per variable family keeps the most severe)
        for rule in rs["rules"]:
            if rule["domain"] != d or not _in_age(rule, age_months):
                continue
            if eval_condition(rule["when"], ctx):
                ev.append(Evidence(rule["id"], rule["color"], rule["text_hi"], rule["text_en"], "rule",
                                   rule.get("refer"), rule.get("focus"), rule.get("urgency"),
                                   rule["when"].get("var", rule["id"])))
                color = worse(color, rule["color"])

    # 4) AI findings: may only raise; must cite evidence ids, otherwise held at YELLOW
    for f in inp.ai_findings:
        if not f.get("present"):
            continue
        fc = f.get("color", YELLOW)
        if not f.get("evidence_ids"):
            fc = YELLOW
        ev.append(Evidence(f"AI-{f.get('model', 'llm')}", fc, f.get("text_hi", "AI ने संकेत देखा"),
                           f.get("sign", "AI finding"), "ai", f.get("refer"), f.get("focus")))
        color = worse(color, fc)

    # 5) worker concern
    if inp.worried:
        reason = inp.worried_reason or ""
        ev.append(Evidence("WORRIED", YELLOW, f"आशा को चिंता है: {reason}".strip(), f"Worker concern: {reason}".strip(), "worker"))
        color = worse(color, YELLOW)

    # drop duplicate lower-severity hits from the same rule family (e.g. MUAC red + MUAC yellow)
    ev = _dedupe(ev)
    return DomainOutcome(d, color, ev)


def _dedupe(ev: list[Evidence]) -> list[Evidence]:
    best: dict[str, Evidence] = {}
    order: list[str] = []
    for e in ev:
        key = e.family or (e.rule_id + e.source)
        if key not in best:
            best[key] = e
            order.append(key)
        elif _RANK[e.color] > _RANK[best[key].color]:
            best[key] = e
    return [best[k] for k in order]


def combine(outcomes: list[DomainOutcome], visit_worried: bool = False) -> str:
    final = GREEN
    for o in outcomes:
        final = worse(final, o.color)
    if visit_worried:
        final = worse(final, YELLOW)
    return final


def cross_domain_notes(outcomes: list[DomainOutcome]) -> list[dict]:
    hit = {e.rule_id for o in outcomes for e in o.evidence}
    return [x for x in load_ruleset().get("cross_domain", []) if all(r in hit for r in x["if_rules"])]


def danger_sign_emergency(danger: dict) -> bool:
    return any(v in (True, "yes") for v in (danger or {}).values())
