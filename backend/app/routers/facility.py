"""Facility module (design 3.x / screens 17-20): shared inbox, accept / decline, QR check-in, outcome."""
import secrets
from datetime import date, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..db import get_db
from ..models import Facility, Media, Referral, User
from ..schemas import AcceptIn, CheckinIn, DeclineIn, OutcomeIn
from ..security import audit, require_role
from ..services import schedule, whatsapp

router = APIRouter(prefix="/facility", tags=["facility"])
facility_only = require_role("facility")
ACTIVE = ("CREATED", "NOTIFIED", "BOOKED", "REACHED", "SEEN")
CHIP_HI = {"NOTIFIED": "नया", "BOOKED": "बुक", "REACHED": "पहुँचे", "SEEN": "देखा", "CLOSED": "बंद", "DECLINED": "अस्वीकार"}
DECLINE_EN = {"not_enough_evidence": "Not enough evidence", "not_indicated": "Not indicated on examination",
              "wrong_facility": "Wrong facility → re-route", "already_treated": "Already under treatment",
              "duplicate": "Duplicate referral"}


def _ref(db: Session, rid: int, user: User) -> Referral:
    r = db.get(Referral, rid)
    if not r or r.facility_id != user.facility_id:
        raise HTTPException(404, "Referral not found")
    return r


def _chip(r: Referral) -> str:
    if r.status == "BOOKED" and r.slot and r.slot.date() == date.today() + timedelta(days=1):
        return "कल"
    return CHIP_HI.get(r.status, r.status)


def brief(r: Referral) -> dict:
    c = r.child
    return {"id": r.id, "child_name": c.name, "age_hi": schedule.age_label_hi(c.dob), "age_en": schedule.age_label_en(c.dob),
            "gender": c.gender, "domains": r.domains, "reason_hi": r.reason_hi, "reason_en": r.reason_en,
            "urgency": r.urgency, "status": r.status, "chip_hi": _chip(r),
            "slot": r.slot.isoformat() if r.slot else None, "created_at": r.created_at.isoformat()}


@router.get("/inbox")
def inbox(include_closed: bool = False, user: User = Depends(facility_only), db: Session = Depends(get_db)):
    stmt = select(Referral).where(Referral.facility_id == user.facility_id)
    if not include_closed:
        stmt = stmt.where(Referral.status.in_(ACTIVE))
    rows = db.scalars(stmt.order_by(Referral.created_at.desc())).all()
    order = {"emergency": 0, "soon": 1, "routine": 2}
    rows = sorted(rows, key=lambda r: (order.get(r.urgency, 3), r.status != "NOTIFIED"))
    f = db.get(Facility, user.facility_id)
    return {"facility": {"id": f.id, "name": f.name, "name_hi": f.name_hi, "type": f.type},
            "items": [brief(r) for r in rows]}


@router.get("/referrals/{rid}")
def detail(rid: int, user: User = Depends(facility_only), db: Session = Depends(get_db)):
    r = _ref(db, rid, user)
    media = []
    if r.status in ACTIVE:  # real data visible only while the referral is active
        for m in db.scalars(select(Media).where(Media.visit_id == r.visit_id, Media.domain.in_(r.domains))).all():
            media.append({"id": m.id, "kind": m.kind, "tag": m.tag, "evidence_code": m.evidence_code,
                          "url": f"/api/v1/facility/media/{m.id}"})
    audit(db, user, "referral_viewed", "referral", r.id)
    db.commit()
    f = db.get(Facility, r.facility_id)
    slots = [(date.today() + timedelta(days=d)).isoformat() + "T" + s for d in (2, 3, 4) for s in (f.opd_slots or ["10:30"])[:1]]
    return {**brief(r), "referred_by": f"ASHA · {r.created_at.date().isoformat()}", "qr_code": r.qr_code,
            "ai_summary": r.ai_summary, "media": media, "outcome": r.outcome,
            "decline_reason": r.decline_reason, "suggested_slots": slots}


@router.get("/media/{media_id}")
def media(media_id: str, user: User = Depends(facility_only), db: Session = Depends(get_db)):
    m = db.get(Media, media_id)
    if not m:
        raise HTTPException(404)
    r = db.scalars(select(Referral).where(Referral.visit_id == m.visit_id, Referral.facility_id == user.facility_id,
                                          Referral.status.in_(ACTIVE))).first()
    if not r or m.domain not in r.domains:
        raise HTTPException(403, "Media visible only for referred conditions while the referral is active")
    audit(db, user, "media_streamed", "media", m.id)
    db.commit()
    return FileResponse(m.path)


@router.post("/referrals/{rid}/accept")
def accept(rid: int, body: AcceptIn, user: User = Depends(facility_only), db: Session = Depends(get_db)):
    r = _ref(db, rid, user)
    if r.status not in ("CREATED", "NOTIFIED", "BOOKED"):
        raise HTTPException(409, f"Cannot accept in status {r.status}")
    r.status, r.slot = "BOOKED", body.slot
    f = db.get(Facility, r.facility_id)
    whatsapp.send(db, r.child, template="appointment_booked",
                  body=(f"{r.child.name} का समय तय: {schedule.when_label_hi(body.slot.date(), body.slot.strftime('%H:%M'))}, "
                        f"{f.name_hi or f.name}। रेफ़रल कार्ड / QR साथ लाएँ। इलाज मुफ़्त है।"))
    audit(db, user, "referral_accepted", "referral", r.id, slot=body.slot.isoformat())
    db.commit()
    return brief(r)


@router.post("/referrals/{rid}/decline")
def decline(rid: int, body: DeclineIn, user: User = Depends(facility_only), db: Session = Depends(get_db)):
    r = _ref(db, rid, user)
    if r.status not in ACTIVE:
        raise HTTPException(409, "Referral not active")
    r.status, r.decline_reason, r.decline_note = "DECLINED", body.reason, body.note
    r.closed_at = datetime.utcnow()
    new = None
    if body.reason == "wrong_facility" and body.reroute_to:
        target = db.scalars(select(Facility).where(Facility.type == body.reroute_to)).first()
        if target:
            new = Referral(child_id=r.child_id, visit_id=r.visit_id, facility_id=target.id, domains=r.domains,
                           reason_hi=r.reason_hi, reason_en=r.reason_en, urgency=r.urgency, status="NOTIFIED",
                           qr_code="RF-" + secrets.token_hex(5).upper(), due_by=r.due_by, ai_summary=r.ai_summary)
            db.add(new)
    else:
        still_open = db.scalars(select(Referral).where(Referral.child_id == r.child_id, Referral.id != r.id,
                                                       Referral.status.in_(ACTIVE))).first()
        if not still_open:
            r.child.care_status = "none"
    # decline reasons become labelled cases for the learning loop (design 8.6)
    audit(db, user, "referral_declined", "referral", r.id, reason=body.reason, label=DECLINE_EN[body.reason],
          note=body.note, rerouted_to=new.facility_id if new else None)
    db.commit()
    return {"status": "DECLINED", "rerouted_referral_id": new.id if new else None, "training_case": True}


def _mark_reached(db: Session, r: Referral, user: User, how: str) -> dict:
    if r.status not in ("CREATED", "NOTIFIED", "BOOKED"):
        raise HTTPException(409, f"Already {r.status}")
    r.status, r.reached_at = "REACHED", datetime.utcnow()
    whatsapp.send(db, r.child, template="reached_confirmed", body=f"{r.child.name} अस्पताल पहुँच गए ✔ डॉक्टर जल्द देखेंगे।")
    audit(db, user, "referral_reached", "referral", r.id, how=how)
    db.commit()
    return brief(r)


@router.post("/checkin")
def checkin(body: CheckinIn, user: User = Depends(facility_only), db: Session = Depends(get_db)):
    r = db.scalars(select(Referral).where(Referral.qr_code == body.qr_code)).first()
    if not r:  # parent may show the child QR instead of the referral QR
        from ..models import Child
        c = db.scalars(select(Child).where(Child.qr_code == body.qr_code)).first()
        if c:
            r = db.scalars(select(Referral).where(Referral.child_id == c.id, Referral.facility_id == user.facility_id,
                                                  Referral.status.in_(ACTIVE))).first()
    if not r or r.facility_id != user.facility_id:
        raise HTTPException(404, "इस सुविधा के लिए कोई रेफ़रल नहीं मिला")
    return _mark_reached(db, r, user, "qr")


@router.post("/referrals/{rid}/reached")
def reached_manual(rid: int, user: User = Depends(facility_only), db: Session = Depends(get_db)):
    return _mark_reached(db, _ref(db, rid, user), user, "manual")


@router.post("/referrals/{rid}/outcome")
def outcome(rid: int, body: OutcomeIn, user: User = Depends(facility_only), db: Session = Depends(get_db)):
    r = _ref(db, rid, user)
    if r.status not in ("REACHED", "SEEN"):
        raise HTTPException(409, "Child must have REACHED before the outcome is recorded")
    r.outcome = {**body.model_dump(mode="json"), "doctor": user.name, "recorded_at": datetime.utcnow().isoformat()}
    r.status, r.closed_at = "CLOSED", datetime.utcnow()  # REACHED -> OUTCOME_RECORDED -> CLOSED in one save
    c = r.child
    if body.next_followup:
        schedule.schedule(db, c, body.next_followup, "followup", f"इलाज फॉलो-अप ({', '.join(r.domains)})", r.domains)
    plan = "; ".join(x for x in (body.treatment, body.medicine) if x)
    whatsapp.send(db, c, template="doctor_plan",
                  body=f"डॉक्टर की सलाह ({user.name}): {plan}." + (f" अगली जाँच: {body.next_followup.isoformat()}" if body.next_followup else ""))
    audit(db, user, "referral_outcome", "referral", r.id, confirmed=body.confirmed, code=body.diagnosis_code)
    db.commit()
    return {**brief(r), "outcome": r.outcome}
