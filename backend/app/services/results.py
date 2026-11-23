"""Visit evaluation (design 8.5) and the result router (design 7.3)."""
import secrets
from collections import defaultdict
from datetime import date, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Child, DomainResult, DueItem, Facility, HomePlan, Media, Referral, Visit
from ..rules import engine
from . import schedule, whatsapp

SUGGESTED_NEXT = {
    "hearing": "OAE / BERA hearing test, speech assessment",
    "speech": "Speech and language assessment",
    "anaemia": "Confirm Hb; start IFA as per Anaemia Mukt Bharat",
    "sam": "NRC admission assessment (MUAC, oedema, appetite test)",
    "vision": "Refraction / ophthalmology review",
}


def evaluate_visit(db: Session, visit: Visit) -> tuple[str, list[engine.DomainOutcome]]:
    age = schedule.age_months(visit.child.dob, visit.started_at.date())
    media_by_domain: dict[str, list[dict]] = defaultdict(list)
    for m in db.scalars(select(Media).where(Media.visit_id == visit.id)).all():
        media_by_domain[m.domain].extend(m.ai_findings or [])
    outcomes = []
    for dr in visit.domains:
        out = engine.evaluate_domain(engine.DomainInput(
            domain=dr.domain, answers=dr.answers or {}, readings=dr.readings or {}, tasks=dr.tasks or {},
            worried=dr.worried, worried_reason=dr.comment if dr.worried else None,
            skipped_reason=dr.skipped_reason, ai_findings=media_by_domain.get(dr.domain, [])), age)
        dr.color = out.color
        dr.evidence = [e.as_dict() for e in out.evidence]
        outcomes.append(out)
    # domains due in this visit but never opened are INCOMPLETE - never green.
    # A follow-up / re-check due item opens only the D being followed (design 3.4).
    due = db.get(DueItem, visit.due_item_id) if visit.due_item_id else None
    expected = due.domains if due and due.domains else list(engine.DOMAINS)
    seen = {dr.domain for dr in visit.domains}
    for d in expected:
        if d not in seen:
            dr = DomainResult(domain=d, color=engine.INCOMPLETE, skipped_reason="not captured",
                              evidence=[{"rule_id": "MISSING", "color": engine.INCOMPLETE,
                                         "text_hi": "जाँच नहीं हुई", "text_en": "Not captured", "source": "missing"}])
            visit.domains.append(dr)
            outcomes.append(engine.DomainOutcome(d, engine.INCOMPLETE, []))
    final = engine.combine(outcomes, visit.worried)
    visit.rules_version = engine.load_ruleset()["version"]
    return final, outcomes


def _facility_for(db: Session, ftype: str) -> Facility | None:
    f = db.scalars(select(Facility).where(Facility.type == ftype)).first()
    return f or db.scalars(select(Facility).where(Facility.type == "DEIC")).first()


def _ai_summary(visit: Visit, domains: list[str], db: Session) -> dict:
    items, focuses = [], set()
    media = {m.domain: m for m in db.scalars(select(Media).where(Media.visit_id == visit.id)).all()}
    any_ai = False
    for dr in visit.domains:
        if dr.domain not in domains:
            continue
        for e in dr.evidence or []:
            if e["color"] == engine.GREEN:
                continue
            ref = media.get(dr.domain)
            detail = {"question": "Parent answer", "rule": "Rule " + e["rule_id"], "worker": "Worker note",
                      "ai": "AI finding", "missing": "Missing input", "skipped": "Skipped"}.get(e["source"], "")
            if dr.tasks.get("name_call_trials") is not None and e["rule_id"] == "R-D4-001":
                detail = f"{dr.tasks.get('name_call_turns', 0)} / {dr.tasks['name_call_trials']} trials"
            if e["rule_id"].startswith("R-D4-00") and "words_named" in dr.tasks and e["rule_id"] != "R-D4-001":
                detail = f"{dr.tasks['words_named']} words named"
            if "hb_gdl" in (dr.readings or {}) and e["rule_id"].startswith("R-D2-01"):
                detail = f"Hb {dr.readings['hb_gdl']} g/dL"
            if ref:
                detail += f" · {ref.kind} {ref.evidence_code}"
            any_ai |= e["source"] == "ai"
            items.append({"title": e["text_en"], "detail": detail, "rule_id": e["rule_id"], "domain": dr.domain})
            if e.get("focus"):
                focuses.add(e["focus"])
    if visit.worried:
        items.append({"title": "Worker concern + voice note", "detail": f"“मुझे चिंता है” — {visit.worried_reason or ''}",
                      "rule_id": "WORRIED", "domain": "*"})
    outs = [engine.DomainOutcome(dr.domain, dr.color, [engine.Evidence(**{k: e.get(k) for k in ("rule_id", "color", "text_hi", "text_en", "source")})
                                                         for e in dr.evidence or []]) for dr in visit.domains]
    notes = [x["note_en"] for x in engine.cross_domain_notes(outs)]
    return {
        "label": "AI SUMMARY (for confirmation, not a diagnosis)",
        "items": items,
        "models_agree": True if not any_ai else None,
        "rules_note": "; ".join(notes) or None,
        "suggested_next": ", ".join(SUGGESTED_NEXT[f] for f in sorted(focuses) if f in SUGGESTED_NEXT) or None,
        "rules_version": visit.rules_version,
    }


def route_result(db: Session, visit: Visit, final: str, only_referrals: bool = False) -> dict:
    """Create referrals / home plan / messages / next due date. Returns a list of actions taken."""
    child: Child = visit.child
    actions: list[dict] = []
    today = date.today()

    red_by_facility: dict[str, list[DomainResult]] = defaultdict(list)
    urgency = "routine"
    focuses_yellow: dict[str, str] = {}
    for dr in visit.domains:
        if dr.color == engine.RED:
            ftype = next((e.get("refer") for e in dr.evidence if e["color"] == engine.RED and e.get("refer")), None)
            ftype = ftype or engine.load_ruleset()["domains"][dr.domain]["default_refer"]
            if any(e.get("urgency") == "emergency" for e in dr.evidence):
                urgency, ftype = "emergency", "DH"
            red_by_facility[ftype].append(dr)
        elif dr.color in (engine.YELLOW, engine.INCOMPLETE):
            focus = next((e.get("focus") for e in dr.evidence if e.get("focus")), None)
            if dr.color == engine.YELLOW:
                focuses_yellow[dr.domain] = focus or "default"

    # RED -> one referral card per facility
    for ftype, drs in red_by_facility.items():
        fac = _facility_for(db, ftype)
        if not fac:
            continue
        doms = [d.domain for d in drs]
        reason_hi = " / ".join(e["text_hi"] for d in drs for e in d.evidence if e["color"] == engine.RED)
        reason_en = " / ".join(e["text_en"] for d in drs for e in d.evidence if e["color"] == engine.RED)
        ref = Referral(child_id=child.id, visit_id=visit.id, facility_id=fac.id, domains=doms,
                       reason_hi=reason_hi[:300], reason_en=reason_en[:300], urgency=urgency,
                       status="NOTIFIED", qr_code="RF-" + secrets.token_hex(5).upper(),
                       due_by=today + timedelta(days=1 if urgency == "emergency" else 7),
                       ai_summary=_ai_summary(visit, doms, db))
        db.add(ref)
        db.flush()
        child.care_status = "under_referral"
        whatsapp.send(db, child, template="referral_card", payload={"referral_qr": ref.qr_code},
                      body=(f"🔴 {whatsapp.child_tag(child)} · रेफ़रल कार्ड\n{reason_hi}\nकहाँ: {fac.name_hi or fac.name}\n"
                            f"कब तक: {schedule.when_label_hi(ref.due_by, None)}\nइलाज मुफ़्त है। एम्बुलेंस: 108 / 102\n"
                            "जवाब दें: पहुँच गए · मदद चाहिए · तारीख बदलें"))
        actions.append({"type": "referral", "text_hi": f"रेफ़रल बना · {fac.type} को भेजा", "referral_id": ref.id})

    if only_referrals:
        return {"actions": actions}

    # YELLOW -> home plan + re-check
    for d, focus in focuses_yellow.items():
        hp = HomePlan(child_id=child.id, visit_id=visit.id, domain=d, focus=focus,
                      recheck_date=today + timedelta(days=28))
        db.add(hp)
        schedule.schedule(db, child, hp.recheck_date, "followup", f"दोबारा जाँच ({d})", [d])
        if child.care_status == "none":
            child.care_status = "home_plan"
    if focuses_yellow:
        first = engine.load_ruleset()["activities"].get(next(iter(focuses_yellow.values())), [])
        act = first[0]["text_hi"] if first else ""
        whatsapp.send(db, child, template="home_activity", body=f"🟡 {whatsapp.child_tag(child)} · आज की गतिविधि\n{act}")
        actions.append({"type": "home_plan", "text_hi": "घर की गतिविधि योजना शुरू"})

    # INCOMPLETE -> retake task, next day
    inc = [dr.domain for dr in visit.domains if dr.color == engine.INCOMPLETE]
    if inc:
        schedule.schedule(db, child, today + timedelta(days=1), "retake", "दोबारा लें: " + ", ".join(inc), inc)
        actions.append({"type": "retake", "text_hi": "छूटी जाँच दोबारा लेने का काम जुड़ा"})

    # Result message to parent: final first, then four Ds
    lines = [f"{whatsapp.child_tag(child)} · जाँच का नतीजा",
             f"अंतिम नतीजा: {whatsapp.EMOJI[final]} {whatsapp.COLOR_HI[final]}"]
    names = engine.load_ruleset()["domains"]
    for dr in sorted(visit.domains, key=lambda x: x.domain):
        reason = next((e["text_hi"] for e in dr.evidence if e["color"] != engine.GREEN), "")
        lines.append(f"{whatsapp.EMOJI[dr.color]} {dr.domain} {names[dr.domain]['name_hi']} · "
                     f"{whatsapp.COLOR_HI[dr.color]}{(' (' + reason + ')') if reason and dr.color != engine.RED else ''}")
    whatsapp.send(db, child, template="screening_result", body="\n".join(lines), payload=whatsapp.menu_payload(child))
    actions.append({"type": "whatsapp", "text_hi": "माता-पिता को WhatsApp भेजा"})

    # next routine date
    nxt = schedule.schedule_next_routine(db, child, today)
    actions.append({"type": "next_due", "text_hi": "अगली जाँच की तारीख तय", "date": nxt.due_date.isoformat()})
    return {"actions": actions}


def finalize(db: Session, visit: Visit, final_now: bool = True) -> dict:
    final, _ = evaluate_visit(db, visit)
    visit.final_color = final
    visit.result_is_final = final_now
    visit.status = "submitted"
    visit.submitted_at = visit.submitted_at or datetime.utcnow()
    child = visit.child
    child.last_screened_at = visit.submitted_at
    schedule.close_due_for_child(db, child.id, visit.worker_id)
    routed = route_result(db, visit, final)
    return {"final": final, **routed}
