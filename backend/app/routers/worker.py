"""Field worker: home / due list, child not present, identify, register, already screened."""
import secrets
from datetime import date

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Area, Child, DueItem, User, Visit
from ..rules.engine import load_ruleset
from ..schemas import AbsentIn, AlreadyScreenedIn, ChildIn
from ..security import audit, require_role
from ..services import schedule, whatsapp
from .auth import user_out

router = APIRouter(tags=["worker"])
worker_only = require_role("worker")

ABSENT_HI = {"not_home": "घर पर नहीं है", "not_at_site": "आँगनवाड़ी / स्कूल नहीं आया", "unwell": "बीमार / अस्पताल में",
             "family_away": "परिवार गाँव से बाहर", "other": "अन्य"}


def child_brief(db: Session, c: Child) -> dict:
    return {
        "id": c.id, "name": c.name, "gender": c.gender, "dob": c.dob.isoformat(),
        "age_hi": schedule.age_label_hi(c.dob), "age_en": schedule.age_label_en(c.dob),
        "age_months": schedule.age_months(c.dob),
        "mother_name": c.mother_name, "father_name": c.father_name,
        "rbsk_id": c.rbsk_id, "qr_code": c.qr_code, "awc": c.awc,
        "care_status": c.care_status,
        "last_screened_at": c.last_screened_at.isoformat() if c.last_screened_at else None,
        "last_screened_where": c.last_screened_where,
    }


def _can_see(worker: User, c: Child) -> bool:
    return c.block_id == worker.area_id  # block-level catchment; any child in the block can be screened


@router.get("/worker/home")
def home(user: User = Depends(worker_only), db: Session = Depends(get_db)):
    items = schedule.due_list(db, user)
    db.commit()
    out = []
    for it in items:
        c = it.child
        latest = db.scalars(select(Visit).where(Visit.child_id == c.id, Visit.status == "submitted")
                            .order_by(Visit.submitted_at.desc())).first()
        unseen = latest and latest.result_is_final and not latest.result_seen_by_worker
        out.append({
            "due_item_id": it.id, "kind": it.kind, "badge_hi": schedule.BADGE_HI.get(it.kind, it.kind),
            "subtitle_hi": f"{schedule.age_label_hi(c.dob)} · {it.label_hi}", "domains": it.domains,
            "child": child_brief(db, c), "final_result_ready": bool(unseen),
        })
    return {
        "worker": user_out(db, user).model_dump(),
        "counts": {"today": len(out), "followup": sum(i["kind"] in ("followup", "retake") for i in out),
                   "overdue": sum(i["kind"] == "overdue" for i in out)},
        "items": out,
    }


@router.get("/offline-pack")
def offline_pack(user: User = Depends(worker_only), db: Session = Depends(get_db)):
    """Everything the phone needs to keep working with no network (design 3.2 onboarding)."""
    return {"ruleset": load_ruleset(), "home": home(user, db)}


@router.post("/due/{item_id}/absent")
def mark_absent(item_id: int, body: AbsentIn, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    item = db.get(DueItem, item_id)
    if not item or item.worker_id != user.id:
        raise HTTPException(404, "Due item not found")
    new, escalate = schedule.mark_absent(db, item, body.note or body.reason)
    when = schedule.when_label_hi(new.due_date, new.due_time)
    c = item.child
    whatsapp.send(db, c, template="visit_rebooked",
                  body=f"नमस्ते, {c.name} की स्वास्थ्य जाँच अब {when} होगी। कृपया बच्चे को घर पर रखें। - आशा {user.name}")
    whatsapp.send(db, c, channel="sms", body=f"Swasth Bal 4D: {c.name} jaanch {new.due_date.isoformat()} 10 baje.")
    audit(db, user, "child_absent", "due_item", item.id, reason=body.reason, escalate=escalate)
    db.commit()
    return {"reason_hi": ABSENT_HI[body.reason], "next_when_hi": when, "next_due_item_id": new.id,
            "parent_notified": ["WhatsApp", "SMS"], "absent_count": new.absent_count, "escalated_to_supervisor": escalate}


@router.get("/children/qr/{code}")
def by_qr(code: str, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    c = db.scalars(select(Child).where(or_(Child.qr_code == code, Child.rbsk_id == code,
                                           Child.abha == code, Child.rch_id == code))).first()
    if not c:
        raise HTTPException(404, "बच्चा नहीं मिला")
    return _card(db, c)


def _card(db: Session, c: Child) -> dict:
    last = db.scalars(select(Visit).where(Visit.child_id == c.id, Visit.status == "submitted")
                      .order_by(Visit.submitted_at.desc())).first()
    age = schedule.age_months(c.dob)
    if last:
        months = max(schedule.age_months(last.submitted_at.date()), 0)
        last_hi = f"पिछली जाँच: {months} महीने पहले"
    elif c.last_screened_at:
        months = schedule.age_months(c.last_screened_at.date())
        last_hi = f"पिछली जाँच: {months} महीने पहले ({c.last_screened_where or ''})"
    else:
        last_hi = "पिछली जाँच: कोई रिकॉर्ड नहीं"
    open_due = db.scalars(select(DueItem).where(DueItem.child_id == c.id, DueItem.status == "due")
                          .order_by(DueItem.due_date)).first()
    plan_domains = open_due.domains if open_due else ["D1", "D2", "D3", "D4"]
    plan_hi = "आज: पूरी 4D जाँच (उम्र के हिसाब से)" if len(plan_domains) == 4 else "आज: " + ", ".join(plan_domains)
    return {"child": child_brief(db, c), "last_screening_hi": last_hi, "today_plan_hi": plan_hi,
            "due_item_id": open_due.id if open_due else None, "domains": plan_domains,
            "age_band": "newborn" if age < 2 else ("child" if age < 72 else ("school" if age < 120 else "adolescent"))}


@router.get("/children/{child_id}/card")
def card(child_id: int, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    c = db.get(Child, child_id)
    if not c or not _can_see(user, c):
        raise HTTPException(404, "बच्चा नहीं मिला")
    return _card(db, c)


@router.get("/children/search")
def search(q: str | None = None, mobile: str | None = None, wide: bool = False,
           user: User = Depends(worker_only), db: Session = Depends(get_db)):
    stmt = select(Child)
    if not wide:
        stmt = stmt.where(Child.block_id == user.area_id)
    if q:
        like = f"%{q}%"
        stmt = stmt.where(or_(Child.name.ilike(like), Child.mother_name.ilike(like), Child.father_name.ilike(like)))
    if mobile:
        stmt = stmt.where(Child.parent_mobile == mobile)
    if not q and not mobile:
        raise HTTPException(400, "Give a name or mobile")
    rows = db.scalars(stmt.limit(20)).all()
    # minimum identity only for children outside the catchment (wide mobile search)
    return [{"id": c.id, "name": c.name, "age_hi": schedule.age_label_hi(c.dob), "mother_name": c.mother_name,
             "village": (db.get(Area, c.village_id).name_hi if c.village_id else None)} for c in rows]


def _possible_duplicates(db: Session, body: ChildIn) -> list[Child]:
    for field in ("abha", "rch_id", "rbsk_id"):
        v = getattr(body, field)
        if v:
            hit = db.scalars(select(Child).where(getattr(Child, field) == v)).first()
            if hit:
                return [hit]
    stmt = select(Child).where(Child.dob == body.dob)
    cands = db.scalars(stmt).all()
    first = body.name.split()[0].lower()
    return [c for c in cands if c.name.split()[0].lower() == first or
            (body.parent_mobile and c.parent_mobile == body.parent_mobile) or
            (body.mother_name and c.mother_name and c.mother_name.lower() == body.mother_name.lower())]


@router.post("/children", status_code=201)
def register(body: ChildIn, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    dups = _possible_duplicates(db, body)
    if dups and not body.force:
        raise HTTPException(409, {"message": "मिलते-जुलते बच्चे मिले - पहले देखें",
                                  "candidates": [child_brief(db, c) for c in dups]})
    c = Child(name=body.name, gender=body.gender, dob=body.dob, mother_name=body.mother_name,
              father_name=body.father_name, parent_mobile=body.parent_mobile,
              village_id=body.village_id or user.village_id, block_id=user.area_id, awc=body.awc,
              rbsk_id=body.rbsk_id, abha=body.abha, rch_id=body.rch_id, source="field",
              qr_code="SB4D-" + secrets.token_hex(4).upper())
    db.add(c)
    db.flush()
    whatsapp.send(db, c, channel="whatsapp", template="welcome_qr", payload={"qr": c.qr_code},
                  body=(f"नमस्ते! स्वस्थ बाल 4D में {c.name} का पंजीकरण हो गया है। यह QR कोड हर जाँच पर दिखाएँ। "
                        "जुड़ने के लिए “हाँ” लिखें।"))
    audit(db, user, "child_registered", "child", c.id)
    db.commit()
    return _card(db, c)


@router.post("/children/{child_id}/already-screened")
def already_screened(child_id: int, body: AlreadyScreenedIn, user: User = Depends(worker_only),
                     db: Session = Depends(get_db)):
    c = db.get(Child, child_id)
    if not c:
        raise HTTPException(404)
    for it in db.scalars(select(DueItem).where(DueItem.child_id == c.id, DueItem.status == "due")).all():
        it.status = "done_elsewhere"
    audit(db, user, "already_screened_claim", "child", c.id, when=body.when.isoformat(), where=body.where,
          by=body.by_whom)
    db.commit()
    return {"status": "DONE_ELSEWHERE", "verification": "pending_rbsk_check"}


@router.get("/questionnaire")
def questionnaire(age_months: int, user: User = Depends(require_role("worker"))):
    rs = load_ruleset()
    inside = lambda x: x.get("age_months", [0, 216])[0] <= age_months <= x.get("age_months", [0, 216])[1]  # noqa: E731
    return {
        "danger_signs": rs["danger_signs"],
        "domains": {d: {**meta,
                        "questions": [q for q in rs["questions"] if q["domain"] == d and inside(q)],
                        "captures": [c for c in rs["captures"] if c["domain"] == d and inside(c)],
                        "devices": [v for v in rs["devices"] if v["domain"] == d and inside(v)]}
                    for d, meta in rs["domains"].items()},
        "words_expected": next((v for a, v in reversed(rs["derived"]["words_expected"]) if age_months >= a), 0),
    }


@router.get("/today")
def today():
    return {"date": date.today().isoformat()}
