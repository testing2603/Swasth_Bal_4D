"""Department dashboard (design UC-8 / screen 21). Anonymous totals only; groups under 5 are hidden."""
from collections import defaultdict
from datetime import date

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Area, Child, DomainResult, DueItem, HomePlan, Referral, User, Visit
from ..schemas import AskIn
from ..security import require_role

router = APIRouter(prefix="/dashboard", tags=["department"])
official_only = require_role("official", "admin")
MIN_GROUP = 5


def _blocks(db: Session, user: User) -> list[Area]:
    area = db.get(Area, user.area_id) if user.area_id else None
    if area and area.level == "block":
        return [area]
    blocks = db.scalars(select(Area).where(Area.level == "block")).all()
    if area and area.level == "district":
        blocks = [b for b in blocks if b.parent_id == area.id]
    return blocks


def summary_data(db: Session, user: User) -> dict:
    blocks = _blocks(db, user)
    block_ids = {b.id for b in blocks}
    children = [c for c in db.scalars(select(Child)).all() if c.block_id in block_ids]
    child_ids = {c.id for c in children}
    visits = [v for v in db.scalars(select(Visit).where(Visit.status == "submitted")).all() if v.child_id in child_ids]
    screened_children = {v.child_id for v in visits}
    due = [d for d in db.scalars(select(DueItem)).all() if d.child_id in child_ids]
    expected = {d.child_id for d in due if d.due_date <= date.today()} | screened_children

    by_block = []
    for b in blocks:
        kids = {c.id for c in children if c.block_id == b.id}
        exp = len(kids & expected)
        scr = len(kids & screened_children)
        cov = round(100 * scr / exp) if exp else None
        by_block.append({"block": b.name, "block_hi": b.name_hi, "expected": exp, "screened": scr,
                         "coverage_pct": cov if exp >= MIN_GROUP else None, "missed": max(exp - scr, 0)})

    refs = [r for r in db.scalars(select(Referral)).all() if r.child_id in child_ids]
    reached = [r for r in refs if r.reached_at]
    funnel = {
        "referred": len(refs),
        "reached": len(reached),
        "under_treatment": sum(1 for r in refs if r.status == "CLOSED" and (r.outcome or {}).get("confirmed")),
        "overdue_week": sum(1 for r in refs if r.status in ("NOTIFIED", "BOOKED") and (date.today() - r.created_at.date()).days > 7),
    }
    d_arr = [(r.reached_at - r.created_at).total_seconds() / 86400 for r in reached]
    d_out = [(r.closed_at - r.reached_at).total_seconds() / 86400 for r in reached if r.closed_at and r.status == "CLOSED"]
    closed = [r for r in refs if r.status in ("CLOSED", "DECLINED") and r.closed_at]
    agree = [r for r in closed if (r.status == "CLOSED" and (r.outcome or {}).get("confirmed"))]

    flagged = defaultdict(int)
    total_d = defaultdict(int)
    vids = {v.id for v in visits}
    for dr in db.scalars(select(DomainResult)).all():
        if dr.visit_id in vids:
            total_d[dr.domain] += 1
            flagged[dr.domain] += dr.color in ("RED", "YELLOW")
    plans = [p for p in db.scalars(select(HomePlan)).all() if p.child_id in child_ids]
    improving = [p for p in plans if p.status in ("IMPROVING", "TARGET_MET")]

    def pct(a, b):
        return round(100 * a / b) if b else 0

    return {
        "area": user.area_id, "month": date.today().strftime("%b %Y"), "anonymous": True,
        "kpis": {
            "children_screened": len(screened_children),
            "coverage_pct": pct(len(screened_children), len(expected)),
            "referrals_reached_pct": pct(len(reached), len(refs)),
            "yellow_improving_pct": pct(len(improving), len(plans)),
        },
        "coverage_by_block": by_block,
        "funnel": funnel,
        "timing_days": {"referral_to_arrival": round(sum(d_arr) / len(d_arr), 1) if d_arr else None,
                        "arrival_to_outcome": round(sum(d_out) / len(d_out), 1) if d_out else None},
        "ai_doctor_agreement_pct": pct(len(agree), len(closed)) if closed else None,
        "flagged_per_d_pct": {d: round(100 * flagged[d] / total_d[d], 1) if total_d[d] else 0 for d in ("D1", "D2", "D3", "D4")},
    }


@router.get("/summary")
def summary(user: User = Depends(official_only), db: Session = Depends(get_db)):
    return summary_data(db, user)


@router.post("/ask")
def ask(body: AskIn, user: User = Depends(official_only), db: Session = Depends(get_db)):
    """Plain-Hindi / English questions over the anonymous numbers. Intent matching here; an LLM
    over the reporting replica can replace this, with Python checking every number it quotes."""
    data = summary_data(db, user)
    q = body.q.lower()
    blocks = [b for b in data["coverage_by_block"] if b["expected"]]
    if any(k in q for k in ("छूट", "missed", "miss")):
        if not blocks:
            return {"answer": "अभी कोई आँकड़ा नहीं है।"}
        b = max(blocks, key=lambda x: x["missed"])
        return {"answer": f"{b['block_hi'] or b['block']} ब्लॉक में सबसे ज़्यादा बच्चे छूटे: {b['missed']} "
                          f"({b['screened']} / {b['expected']} जाँचे गए)।", "data": b}
    if any(k in q for k in ("रेफ़रल", "रेफरल", "referral")):
        f = data["funnel"]
        return {"answer": f"{f['referred']} रेफ़रल, {f['reached']} पहुँचे, 7 दिन से ज़्यादा लंबित: {f['overdue_week']}।", "data": f}
    if any(k in q for k in ("कवरेज", "coverage")):
        return {"answer": f"कुल कवरेज {data['kpis']['coverage_pct']}% है।", "data": data["kpis"]}
    return {"answer": "माफ़ कीजिए, यह सवाल अभी समझ नहीं आया। पूछें: छूटे बच्चे, रेफ़रल, कवरेज।"}
