"""Synthetic demo data matching the design screens (no real children's data - design 8.6)."""
import random
from datetime import date, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import Area, Child, DomainResult, DueItem, Facility, HomePlan, Referral, User, Visit
from .security import hash_secret

DEMO_PIN = "1234"
MOBILES = {"worker": "9000000001", "facility": "9000000002", "official": "9000000003", "admin": "9000000009",
           "worker2": "9000000004"}


def _months_ago(n: int, extra_days: int = 0) -> date:
    t = date.today()
    y, m = divmod(t.month - 1 - n, 12)
    return date(t.year + y, m + 1, min(t.day, 28)) - timedelta(days=extra_days)


def seed(db: Session) -> None:
    if db.scalars(select(User)).first():
        return
    rnd = random.Random(42)
    up = Area(lgd_code="09", name="Uttar Pradesh", name_hi="उत्तर प्रदेश", level="state")
    db.add(up)
    db.flush()
    lko = Area(lgd_code="0941", name="Lucknow", name_hi="लखनऊ", level="district", parent_id=up.id)
    db.add(lko)
    db.flush()
    block_names = [("BKT", "बख्शी का तालाब"), ("Malihabad", "मलिहाबाद"), ("Mohanlalganj", "मोहनलालगंज"),
                   ("Gosainganj", "गोसाईंगंज"), ("Sarojini Ng.", "सरोजिनी नगर"), ("Chinhat", "चिनहट")]
    blocks = []
    for i, (n, h) in enumerate(block_names):
        b = Area(lgd_code=f"0941{i:02d}", name=n, name_hi=h, level="block", parent_id=lko.id)
        db.add(b)
        blocks.append(b)
    db.flush()
    bkt = blocks[0]
    rampur = Area(lgd_code="094100001", name="Rampur", name_hi="रामपुर", level="village", parent_id=bkt.id)
    db.add(rampur)
    db.flush()

    deic = Facility(name="DEIC, District Hospital", name_hi="DEIC · ज़िला अस्पताल", type="DEIC", area_id=lko.id,
                    opd_slots=["10:30", "11:00"])
    nrc = Facility(name="NRC, District Hospital", name_hi="पोषण पुनर्वास केंद्र", type="NRC", area_id=lko.id, opd_slots=["10:00"])
    dh = Facility(name="District Hospital Paediatric OPD", name_hi="ज़िला अस्पताल", type="DH", area_id=lko.id, opd_slots=["09:30"])
    phc = Facility(name="PHC Bakshi Ka Talab", name_hi="PHC बख्शी का तालाब", type="PHC", area_id=bkt.id, opd_slots=["10:00"])
    ncd = Facility(name="NCD Clinic, District Hospital", name_hi="NCD क्लिनिक", type="NCD", area_id=lko.id, opd_slots=["11:00"])
    db.add_all([deic, nrc, dh, phc, ncd])
    db.flush()

    pin = hash_secret(DEMO_PIN)
    sunita = User(mobile=MOBILES["worker"], name="सुनीता", role="worker", worker_type="ASHA", area_id=bkt.id,
                  village_id=rampur.id, pin_hash=pin)
    other = User(mobile=MOBILES["worker2"], name="कमला", role="worker", worker_type="AWW", area_id=bkt.id, pin_hash=pin)
    mehta = User(mobile=MOBILES["facility"], name="डॉ. मेहता", role="facility", facility_id=deic.id, hpr_id="HPR-71-0042", pin_hash=pin)
    cmo = User(mobile=MOBILES["official"], name="ACMO-RBSK Lucknow", role="official", area_id=lko.id, pin_hash=pin)
    admin = User(mobile=MOBILES["admin"], name="System admin", role="admin", pin_hash=pin)
    db.add_all([sunita, other, mehta, cmo, admin])
    db.flush()

    def child(name, g, dob, mother=None, father=None, mobile=None, village=rampur, block=bkt, awc="आँगनवाड़ी रामपुर", rbsk=None):
        c = Child(name=name, gender=g, dob=dob, mother_name=mother, father_name=father, parent_mobile=mobile,
                  village_id=village.id if village else None, block_id=block.id, awc=awc, rbsk_id=rbsk,
                  qr_code=f"SB4D-{rnd.randrange(16**8):08X}", source="POSHAN", whatsapp_opt_in=bool(mobile))
        db.add(c)
        db.flush()
        return c

    # --- Sunita's day list (screen 1)
    aman = child("अमन कुमार", "M", _months_ago(27), "रीना देवी", "सुरेश", "9800000001", rbsk="RBSK-0941-22817")
    aman.last_screened_at = datetime.utcnow() - timedelta(days=182)
    aman.last_screened_where = "आँगनवाड़ी"
    newborn = child("नवजात – रीना देवी", "F", date.today() - timedelta(days=7), "रीना देवी", None, "9800000002")
    priya = child("प्रिया", "F", _months_ago(48), "सीता", "रमेश", "9800000003")
    rahul = child("राहुल", "M", _months_ago(12), "गीता", "मोहन", "9800000004")
    db.add_all([
        DueItem(child_id=aman.id, worker_id=sunita.id, due_date=date.today(), kind="missed",
                label_hi="आँगनवाड़ी जाँच छूटी · घर पर जाँच"),
        DueItem(child_id=newborn.id, worker_id=sunita.id, due_date=date.today(), kind="newborn",
                label_hi="नवजात घर-भ्रमण (दिन 7)"),
        DueItem(child_id=priya.id, worker_id=sunita.id, due_date=date.today(), kind="followup",
                label_hi="दोबारा जाँच (पीला)", domains=["D2"]),
        DueItem(child_id=rahul.id, worker_id=sunita.id, due_date=date.today() - timedelta(days=9), kind="overdue",
                label_hi="रेफ़रल की तारीख निकली"),
    ])
    priya.care_status = "home_plan"

    # --- DEIC inbox examples (screen 17)
    seema = child("सीमा", "F", _months_ago(60), "शबनम", None, "9800000005", village=None, block=blocks[1])
    ravi = child("रवि", "M", _months_ago(108), "उषा", None, "9800000006", village=None, block=blocks[2])
    for c, dom, rh, re_, st, slot in [
        (seema, ["D1"], "क्लब फुट", "Club foot", "BOOKED", datetime.combine(date.today() + timedelta(days=1), datetime.min.time()).replace(hour=10, minute=30)),
        (ravi, ["D1"], "दिल की आवाज़", "Heart murmur", "REACHED", None),
    ]:
        v = Visit(child_id=c.id, worker_id=other.id, status="submitted", final_color="RED", result_is_final=True,
                  submitted_at=datetime.utcnow() - timedelta(days=3), consent={"photo": True, "video": True, "voice": True})
        v.domains = [DomainResult(domain=d, color="RED" if d in dom else "GREEN",
                                  evidence=[{"rule_id": "Q", "color": "RED", "text_hi": rh, "text_en": re_, "source": "question"}] if d in dom else [])
                     for d in ("D1", "D2", "D3", "D4")]
        db.add(v)
        db.flush()
        db.add(Referral(child_id=c.id, visit_id=v.id, facility_id=deic.id, domains=dom, reason_hi=rh, reason_en=re_,
                        status=st, slot=slot, qr_code=f"RF-DEMO{c.id:04d}", due_by=date.today() + timedelta(days=4),
                        created_at=datetime.utcnow() - timedelta(days=3),
                        reached_at=datetime.utcnow() - timedelta(hours=2) if st == "REACHED" else None,
                        ai_summary={"label": "AI SUMMARY (for confirmation, not a diagnosis)",
                                    "items": [{"title": re_, "detail": "Parent answer + photo", "rule_id": "Q", "domain": "D1"}],
                                    "models_agree": True}))
        c.care_status = "under_referral"

    # --- background population for the dashboard (anonymous totals)
    coverage = {0: 0.69, 1: 0.52, 2: 0.61, 3: 0.44, 4: 0.57, 5: 0.66}
    for bi, b in enumerate(blocks):
        for k in range(40):
            c = child(f"बच्चा {bi}-{k}", rnd.choice("MF"), _months_ago(rnd.randrange(1, 180)), village=None, block=b, awc=None)
            db.add(DueItem(child_id=c.id, worker_id=None, due_date=date.today() - timedelta(days=rnd.randrange(0, 60)),
                           kind="routine", label_hi="नियमित", status="due"))
            if rnd.random() < coverage[bi]:
                colors = {d: rnd.choices(["GREEN", "YELLOW", "RED"], [0.8, 0.15, 0.05])[0] for d in ("D1", "D2", "D3", "D4")}
                if bi != 1 and rnd.random() < 0.3:
                    colors["D2"] = "YELLOW"
                final = "RED" if "RED" in colors.values() else ("YELLOW" if "YELLOW" in colors.values() else "GREEN")
                v = Visit(child_id=c.id, worker_id=other.id, status="submitted", final_color=final, result_is_final=True,
                          submitted_at=datetime.utcnow() - timedelta(days=rnd.randrange(1, 40)))
                v.domains = [DomainResult(domain=d, color=col) for d, col in colors.items()]
                db.add(v)
                db.flush()
                c.last_screened_at = v.submitted_at
                if final == "YELLOW":
                    scores = [40, 40 + rnd.choice([-5, 0, 10, 20])]
                    db.add(HomePlan(child_id=c.id, visit_id=v.id, domain="D2", focus="anaemia",
                                    recheck_date=date.today() + timedelta(days=14), weekly_scores=scores,
                                    status="IMPROVING" if scores[1] > scores[0] else "NOT_IMPROVING"))
                if final == "RED":
                    created = datetime.utcnow() - timedelta(days=rnd.randrange(5, 30))
                    reached = created + timedelta(days=rnd.randrange(2, 11)) if rnd.random() < 0.6 else None
                    closed = reached + timedelta(days=rnd.choice([1, 1, 2])) if reached and rnd.random() < 0.8 else None
                    db.add(Referral(child_id=c.id, visit_id=v.id, facility_id=rnd.choice([nrc.id, dh.id, phc.id]),
                                    domains=[d for d, col in colors.items() if col == "RED"], reason_hi="—", reason_en="—",
                                    status="CLOSED" if closed else ("REACHED" if reached else "NOTIFIED"),
                                    qr_code=f"RF-BG{c.id:05d}", created_at=created, reached_at=reached, closed_at=closed,
                                    outcome={"confirmed": rnd.random() < 0.82} if closed else None))
    db.commit()
