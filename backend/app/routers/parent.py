"""Parent WhatsApp companion (design section 10) + referral reminder ladder (design UC-4)."""
from datetime import date, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import PlainTextResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import get_settings
from ..db import get_db
from ..models import Child, HomePlan, Message, Referral, User
from ..rules.engine import load_ruleset
from ..schemas import WhatsAppIn
from ..security import audit, require_role
from ..services import schedule, whatsapp

router = APIRouter(tags=["parent"])


@router.get("/whatsapp/webhook")
def verify(mode: str = Query(alias="hub.mode", default=""), token: str = Query(alias="hub.verify_token", default=""),
           challenge: str = Query(alias="hub.challenge", default="")):
    if mode == "subscribe" and token == get_settings().wa_verify_token:
        return PlainTextResponse(challenge)
    raise HTTPException(403)


def _children_of(db: Session, mobile: str) -> list[Child]:
    return db.scalars(select(Child).where(Child.parent_mobile == mobile)).all()


def handle(db: Session, msg: WhatsAppIn) -> list[dict]:
    kids = _children_of(db, msg.from_mobile)
    if not kids:
        return [{"text": "यह नंबर किसी बच्चे से जुड़ा नहीं है। आशा दीदी से संपर्क करें।"}]
    child = next((k for k in kids if k.id == msg.child_id), None)
    if child is None and len(kids) == 1:
        child = kids[0]
    db.add(Message(child_id=child.id if child else None, to_mobile=msg.from_mobile, direction="in",
                   body=msg.text or f"[{msg.media_kind}]", payload={"media_kind": msg.media_kind}))
    if child is None:  # nothing is processed until the child is confirmed
        return [{"text": "ये किस बच्चे के लिए है?", "buttons": [{"id": k.id, "title": whatsapp.child_tag(k)} for k in kids]}]

    text = (msg.text or "").strip()
    replies: list[dict] = []
    if text in ("हाँ", "haan", "yes", "Yes", "हां"):
        for k in kids:
            k.whatsapp_opt_in = True
        replies.append({"text": "धन्यवाद! अब नतीजे और गतिविधियाँ यहीं मिलेंगी।", "menu": whatsapp.menu_payload(child)})
    elif msg.media_kind:
        plan = db.scalars(select(HomePlan).where(HomePlan.child_id == child.id, HomePlan.active.is_(True))).first()
        if plan:
            score = min(100.0, (plan.weekly_scores[-1] if plan.weekly_scores else 40) + 10)
            plan.weekly_scores = [*plan.weekly_scores, score]
            plan.progress_score = score
            if len(plan.weekly_scores) >= 2 and plan.weekly_scores[-1] > plan.weekly_scores[0]:
                plan.status = "IMPROVING"
        replies.append({"text": f"✅ मिल गया! {whatsapp.child_tag(child)} की गतिविधि दर्ज हो गई 👏"})
    elif text in ("1", "गतिविधि"):
        plan = db.scalars(select(HomePlan).where(HomePlan.child_id == child.id, HomePlan.active.is_(True))).first()
        acts = load_ruleset()["activities"].get(plan.focus if plan else "default") or load_ruleset()["activities"]["default"]
        idx = len(plan.weekly_scores) % len(acts) if plan else 0
        replies.append({"text": f"{whatsapp.child_tag(child)} · आज की गतिविधि\n{acts[idx]['text_hi']}"})
    elif text in ("2", "सवाल"):
        replies.append({"text": "अपना सवाल लिखें या बोलकर भेजें।"})
    elif text in ("3",):
        replies.append({"text": "डॉक्टर का पर्चा या रिपोर्ट की फ़ोटो भेजें।"})
    elif text in ("4", "स्थिति"):
        ref = db.scalars(select(Referral).where(Referral.child_id == child.id).order_by(Referral.id.desc())).first()
        status = {"none": "कोई काम लंबित नहीं", "home_plan": "घर की गतिविधि योजना चालू",
                  "under_referral": "रेफ़रल पर" + (f" ({ref.status})" if ref else "")}[child.care_status]
        replies.append({"text": f"{whatsapp.child_tag(child)}: {status}"})
    elif text in ("5",):
        replies.append({"text": "आशा दीदी को संदेश भेज दिया गया है, वे जल्द संपर्क करेंगी।"})
    elif text in ("पहुँच गए", "reached"):
        replies.append({"text": "धन्यवाद! अस्पताल में QR दिखाएँ ताकि पहुँचना दर्ज हो।"})
    elif text:
        replies.append(answer_question(db, child, text))
    else:
        replies.append({"menu": whatsapp.menu_payload(child)})
    for r in replies:
        whatsapp.send(db, child, body=r.get("text", ""), payload={k: v for k, v in r.items() if k != "text"})
    return replies


def answer_question(db: Session, child: Child, text: str) -> dict:
    """RAG over approved content + the confirmed prescription; anything else goes to the ASHA."""
    t = text.lower()
    ref = db.scalars(select(Referral).where(Referral.child_id == child.id, Referral.status == "CLOSED")
                     .order_by(Referral.id.desc())).first()
    if ref and ref.outcome and ref.outcome.get("medicine") and any(k in t for k in ("दवा", "सिरप", "आयरन", "medicine")):
        when = ref.outcome.get("recorded_at", "")[:10]
        return {"text": f"डॉक्टर की पर्ची के अनुसार: {ref.outcome['medicine']}। उल्टी या पेट दर्द हो तो आशा दीदी को बताएँ।",
                "source": f"पुष्टि की गई पर्ची · {when}"}
    for c in load_ruleset()["approved_content"]:
        if any(k in t for k in c["keywords"]):
            return {"text": c["text_hi"], "source": c["source"]}
    return {"text": "यह सवाल आशा दीदी को भेज दिया गया है, वे जवाब देंगी।", "source": None, "forwarded_to_asha": True}


@router.post("/whatsapp/inbound")
def inbound(msg: WhatsAppIn, db: Session = Depends(get_db)):
    replies = handle(db, msg)
    db.commit()
    return {"replies": replies}


@router.post("/whatsapp/webhook")
async def webhook(request: Request, db: Session = Depends(get_db)):
    """Maps the Meta Cloud API webhook payload onto the simplified handler."""
    data = await request.json()
    out = []
    for entry in data.get("entry", []):
        for ch in entry.get("changes", []):
            for m in ch.get("value", {}).get("messages", []):
                text = m.get("text", {}).get("body") or m.get("interactive", {}).get("list_reply", {}).get("id")
                kind = {"image": "photo", "video": "video", "audio": "audio", "document": "document"}.get(m.get("type"))
                out += handle(db, WhatsAppIn(from_mobile=m["from"][-10:], text=text, media_kind=kind))
    db.commit()
    return {"ok": True, "replies": len(out)}


@router.get("/dev/messages")
def dev_messages(mobile: str, db: Session = Depends(get_db)):
    """Developer view of a parent's chat (dev mode only) - stands in for the WhatsApp screen."""
    if not get_settings().dev_mode:
        raise HTTPException(404)
    rows = db.scalars(select(Message).where(Message.to_mobile == mobile).order_by(Message.id)).all()
    return [{"direction": m.direction, "channel": m.channel, "template": m.template, "body": m.body,
             "payload": m.payload, "at": m.created_at.isoformat()} for m in rows]


@router.post("/jobs/referral-ladder")
def referral_ladder(user: User = Depends(require_role("admin", "official")), db: Session = Depends(get_db)):
    """Run daily (Celery beat / cron): D-2, D-1, due-day reminders; missed -> daily push + IVR for 3 days
    -> ASHA home visit at D+3; +7 days block, +14 days district escalation."""
    today = date.today()
    actions = []
    for r in db.scalars(select(Referral).where(Referral.status.in_(("NOTIFIED", "BOOKED")))).all():
        due = r.slot.date() if r.slot else r.due_by
        if not due:
            continue
        delta = (today - due).days
        c = r.child
        if delta in (-2, -1, 0):
            whatsapp.send(db, c, template="referral_reminder", body=f"याद दिलाना: {c.name} को {schedule.when_label_hi(due, None)} अस्पताल ले जाना है।")
            actions.append((r.id, f"reminder D{delta:+d}"))
        elif 1 <= delta <= 3:
            whatsapp.send(db, c, template="referral_missed", body=f"{c.name} अस्पताल नहीं पहुँचे। कृपया जल्द जाएँ या तारीख बदलें।")
            whatsapp.send(db, c, channel="ivr", body="IVR call: referral missed")
            actions.append((r.id, "missed push + IVR"))
            if delta == 3:
                schedule.schedule(db, c, today, "urgent", "रेफ़रल छूटा · घर जाकर देखें", r.domains)
                actions.append((r.id, "ASHA home visit task"))
        elif delta >= 14 and r.escalation_level != "district":
            r.escalation_level = "district"
            actions.append((r.id, "district escalation"))
        elif delta >= 7 and r.escalation_level is None:
            r.escalation_level = "block"
            actions.append((r.id, "block escalation"))
    audit(db, user, "referral_ladder_run", "job", datetime.utcnow().isoformat(), n=len(actions))
    db.commit()
    return {"actions": [{"referral_id": a, "action": b} for a, b in actions]}
