"""Guided 4D screening: consent -> danger signs -> D1..D4 -> submit -> result. Also offline sync."""
import hashlib
from datetime import datetime, timedelta
from pathlib import Path

from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, HTTPException, UploadFile
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import get_settings
from ..db import SessionLocal, get_db
from ..models import Child, DomainResult, Media, OtpCode, Referral, User, Visit
from ..rules import engine
from ..schemas import ConsentIn, DangerIn, DomainIn, FullVisitIn, SubmitIn, SyncIn, VisitCreate
from ..security import audit, hash_secret, new_otp, require_role, verify_secret
from ..services import ai, results, schedule, whatsapp

router = APIRouter(prefix="/visits", tags=["screening"])
worker_only = require_role("worker")
COLOR_HI = whatsapp.COLOR_HI


def _visit(db: Session, visit_id: str, user: User) -> Visit:
    v = db.get(Visit, visit_id)
    if not v or v.worker_id != user.id:
        raise HTTPException(404, "Visit not found")
    return v


def _domain(v: Visit, d: str) -> DomainResult:
    for dr in v.domains:
        if dr.domain == d:
            return dr
    dr = DomainResult(domain=d)
    v.domains.append(dr)
    return dr


def create_visit(db: Session, body: VisitCreate, user: User) -> Visit:
    if body.id:
        existing = db.get(Visit, body.id)
        if existing:
            return existing
    child = db.get(Child, body.child_id)
    if not child:
        raise HTTPException(404, "Child not found")
    v = Visit(child_id=child.id, worker_id=user.id, due_item_id=body.due_item_id, mode=body.mode,
              unscheduled=body.due_item_id is None)
    if body.id:
        v.id = body.id
    v.child = child
    db.add(v)
    db.flush()
    return v


@router.post("", status_code=201)
def start(body: VisitCreate, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    v = create_visit(db, body, user)
    db.commit()
    return {"visit_id": v.id, "status": v.status}


def apply_consent(db: Session, v: Visit, body: ConsentIn, user: User) -> dict:
    if body.refused_reason:
        v.status = "refused"
        v.consent_refused_reason = body.refused_reason
        v.consent = {}
        audit(db, user, "consent_refused", "visit", v.id, reason=body.refused_reason)
        return {"status": "refused", "flagged_for": "ANM / supervisor follow-up"}
    if body.method is None:
        raise HTTPException(400, "Consent method (otp / voice) required")
    otp_ok = None
    if body.method == "otp" and body.otp:
        rows = db.scalars(select(OtpCode).where(OtpCode.mobile == (v.child.parent_mobile or ""), OtpCode.used.is_(False),
                                                OtpCode.expires_at > datetime.utcnow())).all()
        match = next((r for r in rows if verify_secret(body.otp, r.code_hash)), None)
        if not match:
            raise HTTPException(400, "माता-पिता का OTP गलत है")
        match.used = True
        otp_ok = True
    otp_ok = otp_ok or (v.consent or {}).get("otp_verified")  # a re-sent consent (sync) keeps the verification
    v.consent = {**body.items, "voice_transcript": body.voice_transcript, "otp_verified": otp_ok}
    v.consent_method = body.method
    if v.consent.get("whatsapp"):
        v.child.whatsapp_opt_in = True
    audit(db, user, "consent_given", "visit", v.id, items=body.items, method=body.method)
    return {"status": "consented", "items": body.items}


@router.post("/{visit_id}/consent/otp")
def consent_otp(visit_id: str, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    """Send a one-time code to the parent's phone; the parent reads it out to give consent."""
    v = _visit(db, visit_id, user)
    mobile = v.child.parent_mobile
    if not mobile:
        raise HTTPException(400, "माता-पिता का मोबाइल नहीं है - आवाज़ से सहमति लें")
    code = new_otp()
    db.add(OtpCode(mobile=mobile, code_hash=hash_secret(code),
                   expires_at=datetime.utcnow() + timedelta(seconds=get_settings().otp_ttl_seconds)))
    whatsapp.send(db, v.child, channel="sms", template="consent_otp",
                  body=f"स्वस्थ बाल 4D: {v.child.name} की जाँच की सहमति के लिए कोड {code}। यह कोड आशा को बताएँ।")
    db.commit()
    return {"sent_to": "XXXXXX" + mobile[-4:], **({"dev_otp": code} if get_settings().dev_mode else {})}


@router.put("/{visit_id}/consent")
def consent(visit_id: str, body: ConsentIn, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    v = _visit(db, visit_id, user)
    out = apply_consent(db, v, body, user)
    db.commit()
    return out


@router.put("/{visit_id}/danger-signs")
def danger(visit_id: str, body: DangerIn, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    v = _visit(db, visit_id, user)
    v.danger_signs = body.answers
    emergency = engine.danger_sign_emergency(body.answers)
    if emergency:
        v.status = "emergency"
        whatsapp.send(db, v.child, channel="sms", template="emergency",
                      body=f"आपातकाल: {v.child.name} को तुरंत ज़िला अस्पताल ले जाएँ। एम्बुलेंस 108 पर कॉल करें।")
    db.commit()
    return {"emergency": emergency, "instruction_hi": "तुरंत 108 पर कॉल करें और ज़िला अस्पताल भेजें" if emergency else None}


def apply_domain(v: Visit, d: str, body: DomainIn) -> DomainResult:
    dr = _domain(v, d)
    dr.answers, dr.readings, dr.tasks = body.answers, body.readings, body.tasks
    dr.comment, dr.worried, dr.skipped_reason = body.comment, body.worried, body.skipped_reason
    return dr


@router.put("/{visit_id}/domains/{d}")
def save_domain(visit_id: str, d: str, body: DomainIn, user: User = Depends(worker_only),
                db: Session = Depends(get_db)):
    if d not in engine.DOMAINS:
        raise HTTPException(400, "Domain must be D1..D4")
    v = _visit(db, visit_id, user)
    if v.status == "refused":
        raise HTTPException(409, "Consent refused - nothing is recorded")
    dr = apply_domain(v, d, body)
    age = schedule.age_months(v.child.dob, v.started_at.date())
    out = engine.evaluate_domain(engine.DomainInput(d, dr.answers, dr.readings, dr.tasks, dr.worried, dr.comment,
                                                    dr.skipped_reason), age)
    dr.color = out.color
    dr.evidence = [e.as_dict() for e in out.evidence]
    db.commit()
    return {"domain": d, "color": out.color, "reasons_hi": [e.text_hi for e in out.evidence if e.color != "GREEN"]}


def _run_ai(media_id: str) -> None:
    db = SessionLocal()
    try:
        m = db.get(Media, media_id)
        if m and m.kind == "photo":
            m.ai_findings = ai.analyze_photo(m.path, m.tag, m.domain, m.evidence_code)
            db.commit()
            v = db.get(Visit, m.visit_id)
            if m.ai_findings and v and v.status == "submitted":
                # AI arrived after submit: re-evaluate; it can only raise the result
                before = v.final_color
                after, _ = results.evaluate_visit(db, v)
                if engine.worse(before or "GREEN", after) != before:
                    v.final_color = after
                    if after == "RED" and not db.scalars(select(Referral).where(Referral.visit_id == v.id)).first():
                        results.route_result(db, v, after, only_referrals=True)
                    whatsapp.send(db, v.child, template="result_updated",
                                  body=f"{v.child.name} का अंतिम नतीजा अपडेट: {whatsapp.EMOJI[after]} {COLOR_HI[after]}")
                db.commit()
    finally:
        db.close()


@router.post("/{visit_id}/media", status_code=201)
async def upload_media(visit_id: str, background: BackgroundTasks, domain: str = Form(...), kind: str = Form(...),
                       tag: str = Form(...), file: UploadFile = File(...), user: User = Depends(worker_only),
                       db: Session = Depends(get_db)):
    v = _visit(db, visit_id, user)
    consent_key = {"photo": "photo", "video": "video", "audio": "voice"}.get(kind)
    if consent_key is None:
        raise HTTPException(400, "kind must be photo / video / audio")
    if not v.consent.get(consent_key):
        raise HTTPException(403, f"No consent for {kind}")
    data = await file.read()
    if len(data) > get_settings().max_upload_mb * 1024 * 1024:
        raise HTTPException(413, "File too large")
    sha = hashlib.sha256(data).hexdigest()
    dup = db.scalars(select(Media).where(Media.visit_id == v.id, Media.sha256 == sha)).first()
    if dup:  # resumable / retried uploads are idempotent
        return {"media_id": dup.id, "evidence_code": dup.evidence_code, "duplicate": True}
    folder = Path(get_settings().media_dir) / v.child.token
    folder.mkdir(parents=True, exist_ok=True)
    ext = Path(file.filename or "").suffix or {"photo": ".webp", "video": ".mp4", "audio": ".opus"}[kind]
    path = folder / f"{sha[:16]}{ext}"
    path.write_bytes(data)
    n = (db.scalar(select(func.count(Media.id))) or 0) + 1
    m = Media(visit_id=v.id, domain=domain, kind=kind, tag=tag, path=str(path), sha256=sha,
              size_bytes=len(data), evidence_code=f"E-{n:02d}")
    db.add(m)
    db.commit()
    background.add_task(_run_ai, m.id)
    return {"media_id": m.id, "evidence_code": m.evidence_code, "duplicate": False}


def worker_view(v: Visit, actions: list[dict] | None = None) -> dict:
    """Plain language only - the worker never sees media or AI outputs (design 3.3)."""
    names = engine.load_ruleset()["domains"]
    ds = []
    for dr in sorted(v.domains, key=lambda x: x.domain):
        reason = next((e["text_hi"] for e in (dr.evidence or []) if e["color"] != "GREEN"), None)
        ds.append({"domain": dr.domain, "name_hi": names[dr.domain]["name_hi"], "color": dr.color,
                   "label_hi": COLOR_HI.get(dr.color or "INCOMPLETE") + (f" · {reason}" if reason else "")})
    final = v.final_color or v.provisional_color
    tell = {
        "GREEN": "बच्चा ठीक है। अगली जाँच की तारीख WhatsApp पर भेजी गई है।",
        "YELLOW": "घर पर रोज़ की गतिविधियाँ WhatsApp पर भेजी गई हैं। 4 हफ़्ते बाद दोबारा जाँच होगी।",
        "INCOMPLETE": "कुछ जाँच छूट गई - कल दोबारा लेनी होगी।",
        "RED": "डॉक्टर को दिखाना ज़रूरी है। रेफ़रल कार्ड और घर की गतिविधियाँ WhatsApp पर भेजी गईं।",
    }.get(final or "INCOMPLETE")
    return {"visit_id": v.id, "final": final, "final_label_hi": COLOR_HI.get(final or "INCOMPLETE"),
            "is_final": v.result_is_final, "domains": ds, "tell_parent_hi": tell, "actions": actions or []}


@router.post("/{visit_id}/submit")
def submit(visit_id: str, body: SubmitIn, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    v = _visit(db, visit_id, user)
    if v.status == "refused":
        raise HTTPException(409, "Consent refused")
    if v.status == "submitted":
        return worker_view(v)
    v.worried, v.worried_reason, v.comment = body.worried, body.worried_reason, body.comment
    out = results.finalize(db, v)
    audit(db, user, "visit_submitted", "visit", v.id, final=out["final"])
    db.commit()
    return worker_view(v, out["actions"])


@router.get("/{visit_id}/result")
def result(visit_id: str, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    v = _visit(db, visit_id, user)
    view = worker_view(v)
    if v.result_is_final:
        v.result_seen_by_worker = True  # shown once after sync, then only current status
        db.commit()
    return view


# ------------------------------------------------------------------ offline outbox
sync_router = APIRouter(prefix="/sync", tags=["sync"])


@sync_router.post("/visits")
def sync_visits(body: SyncIn, user: User = Depends(worker_only), db: Session = Depends(get_db)):
    out = []
    for item in body.visits:
        try:
            v = create_visit(db, item, user)
            if v.status == "submitted":
                out.append({"client_id": item.id, "status": "already_synced", **worker_view(v)})
                continue
            if item.started_at:
                v.started_at = item.started_at
            if item.consent:
                apply_consent(db, v, item.consent, user)
            if v.status == "refused":
                db.commit()
                out.append({"client_id": item.id, "visit_id": v.id, "status": "refused"})
                continue
            v.danger_signs = item.danger_signs
            for d, dom in item.domains.items():
                apply_domain(v, d, dom)
            v.provisional_color = item.provisional_color
            if item.submit:
                v.worried, v.worried_reason, v.comment = item.submit.worried, item.submit.worried_reason, item.submit.comment
                fin = results.finalize(db, v)
                changed = item.provisional_color is not None and fin["final"] != item.provisional_color
                if changed:  # final differs from provisional -> parent and worker notified
                    whatsapp.send(db, v.child, template="result_updated",
                                  body=f"{v.child.name} का अंतिम नतीजा: {whatsapp.EMOJI[fin['final']]} {COLOR_HI[fin['final']]}")
                db.commit()
                out.append({"client_id": item.id, "status": "synced", "changed_from_provisional": changed,
                            **worker_view(v, fin["actions"])})
            else:
                db.commit()
                out.append({"client_id": item.id, "visit_id": v.id, "status": "draft_saved"})
        except HTTPException as exc:
            db.rollback()
            out.append({"client_id": item.id, "status": "error", "detail": exc.detail})
    return {"results": out, "server_time": datetime.utcnow().isoformat()}
