"""End-to-end: the demo journey of Aman Kumar (screens 1-21)."""
import uuid
from datetime import datetime, timedelta

from .conftest import login

API = "/api/v1"


def test_pin_login(client):
    h = login(client, "worker")
    assert client.post(f"{API}/auth/pin/set", json={"pin": "4321"}, headers=h).json()["ok"]
    r = client.post(f"{API}/auth/pin/login", json={"mobile": "9000000001", "pin": "4321"})
    assert r.status_code == 200 and r.json()["user"]["role"] == "worker"
    assert client.post(f"{API}/auth/pin/login", json={"mobile": "9000000001", "pin": "0000"}).status_code == 400


def test_unregistered_number_rejected(client):
    assert client.post(f"{API}/auth/otp/request", json={"mobile": "9999999999"}).status_code == 404


def test_role_separation(client):
    h = login(client, "worker")
    assert client.get(f"{API}/facility/inbox", headers=h).status_code == 403
    assert client.get(f"{API}/dashboard/summary", headers=h).status_code == 403


def test_full_journey(client):
    w = login(client, "worker")
    home = client.get(f"{API}/worker/home", headers=w).json()
    assert home["counts"]["today"] == 4
    assert [i["badge_hi"] for i in home["items"]][:2] == ["छूटी", "नवजात"]
    aman = home["items"][0]

    # screens 2-3: newborn not present -> rebooked tomorrow 10:00, parent told
    nb = home["items"][1]
    r = client.post(f"{API}/due/{nb['due_item_id']}/absent", json={"reason": "not_home"}, headers=w).json()
    assert "10 बजे" in r["next_when_hi"] and r["parent_notified"] == ["WhatsApp", "SMS"]

    # screen 4: identify by QR
    card = client.get(f"{API}/children/qr/{aman['child']['qr_code']}", headers=w).json()
    assert card["child"]["name"] == "अमन कुमार" and "4D" in card["today_plan_hi"]

    # screens 5-6: consent
    vid = str(uuid.uuid4())
    client.post(f"{API}/visits", json={"id": vid, "child_id": card["child"]["id"], "due_item_id": aman["due_item_id"]}, headers=w)
    c = client.put(f"{API}/visits/{vid}/consent", headers=w,
                   json={"items": {"photo": True, "video": True, "voice": True, "whatsapp": True}, "method": "voice",
                         "voice_transcript": "हाँ, मैं अमन की जाँच के लिए सहमत हूँ।"}).json()
    assert c["status"] == "consented"
    assert client.put(f"{API}/visits/{vid}/danger-signs", headers=w,
                      json={"answers": {"ds_drink": False, "ds_vomit": False, "ds_fits": False, "ds_lethargic": False}}).json()["emergency"] is False

    # screen 7: photo upload (idempotent by sha256)
    files = {"file": ("face.webp", b"fake-webp-bytes", "image/webp")}
    m1 = client.post(f"{API}/visits/{vid}/media", headers=w, data={"domain": "D1", "kind": "photo", "tag": "face_front"}, files=files).json()
    m2 = client.post(f"{API}/visits/{vid}/media", headers=w, data={"domain": "D1", "kind": "photo", "tag": "face_front"}, files=files).json()
    assert m2["duplicate"] and m1["media_id"] == m2["media_id"]
    vfiles = {"file": ("namecall.mp4", b"fake-video", "video/mp4")}
    client.post(f"{API}/visits/{vid}/media", headers=w, data={"domain": "D4", "kind": "video", "tag": "name_call"}, files=vfiles)

    # screens 8-10: per-D data
    d1 = client.put(f"{API}/visits/{vid}/domains/D1", headers=w, json={"answers": {"q_d1_cleft": "no", "q_d1_clubfoot": "no"},
                    "comment": "बच्चा चलते समय बायाँ पैर थोड़ा अंदर रखता है"}).json()
    assert d1["color"] == "GREEN"
    d2 = client.put(f"{API}/visits/{vid}/domains/D2", headers=w, json={"readings": {"muac_cm": 13.1, "hb_gdl": 9.8}}).json()
    assert d2["color"] == "YELLOW"
    client.put(f"{API}/visits/{vid}/domains/D3", headers=w, json={"answers": {"q_d3_cough": "no"}})
    d4 = client.put(f"{API}/visits/{vid}/domains/D4", headers=w,
                    json={"tasks": {"name_call_trials": 2, "name_call_turns": 0, "words_named": 2}}).json()
    assert d4["color"] == "RED"

    # screens 11-12: worried + submit -> result
    res = client.post(f"{API}/visits/{vid}/submit", headers=w,
                      json={"worried": True, "worried_reason": "बच्चा आवाज़ पर कम ध्यान देता है"}).json()
    assert res["final"] == "RED" and res["is_final"]
    colors = {d["domain"]: d["color"] for d in res["domains"]}
    assert colors == {"D1": "GREEN", "D2": "YELLOW", "D3": "GREEN", "D4": "RED"}
    kinds = {a["type"] for a in res["actions"]}
    assert {"referral", "home_plan", "whatsapp", "next_due"} <= kinds
    assert "media" not in str(res).lower()  # worker never sees media

    # screen 13: parent got the result
    chat = client.get(f"{API}/dev/messages", params={"mobile": "9800000001"}).json()
    assert any(m["template"] == "screening_result" and "🔴" in m["body"] for m in chat)
    assert any(m["template"] == "referral_card" for m in chat)

    # screens 14-16: parent WhatsApp
    up = client.post(f"{API}/whatsapp/inbound", json={"from_mobile": "9800000001", "media_kind": "audio"}).json()
    assert "मिल गया" in up["replies"][0]["text"]
    q = client.post(f"{API}/whatsapp/inbound", json={"from_mobile": "9800000001", "text": "आयरन सिरप कब दें?"}).json()
    assert q["replies"][0]["source"]

    # screens 17-18: facility inbox + AI summary
    f = login(client, "facility")
    inbox = client.get(f"{API}/facility/inbox", headers=f).json()
    ref = next(i for i in inbox["items"] if i["child_name"] == "अमन कुमार")
    assert ref["chip_hi"] == "नया" and ref["domains"] == ["D4"]
    det = client.get(f"{API}/facility/referrals/{ref['id']}", headers=f).json()
    titles = [i["title"] for i in det["ai_summary"]["items"]]
    assert "No head-turn to name call" in titles and det["ai_summary"]["rules_note"]
    assert det["media"] and det["media"][0]["tag"] == "name_call"  # only referred-domain media
    assert client.get(det["media"][0]["url"], headers=f).status_code == 200

    slot = (datetime.utcnow() + timedelta(days=2)).replace(hour=10, minute=30, second=0, microsecond=0)
    assert client.post(f"{API}/facility/referrals/{ref['id']}/accept", headers=f, json={"slot": slot.isoformat()}).json()["status"] == "BOOKED"
    # outcome before arrival is refused
    oc = {"diagnosis_code": "D4-HI", "diagnosis_text": "Hearing impairment", "confirmed": True,
          "findings": "BERA: moderate hearing loss, both ears", "treatment": "Hearing aid fitting · speech therapy every Tuesday",
          "medicine": "Iron syrup 5 ml once daily, after food", "comment": "Review in 4 weeks",
          "next_followup": (datetime.utcnow() + timedelta(days=28)).date().isoformat()}
    assert client.post(f"{API}/facility/referrals/{ref['id']}/outcome", headers=f, json=oc).status_code == 409
    assert client.post(f"{API}/facility/checkin", headers=f, json={"qr_code": card["child"]["qr_code"]}).json()["status"] == "REACHED"
    closed = client.post(f"{API}/facility/referrals/{ref['id']}/outcome", headers=f, json=oc).json()
    assert closed["status"] == "CLOSED"
    # media access expires when referral closes
    assert client.get(det["media"][0]["url"], headers=f).status_code == 403

    # prescription-grounded answer
    q2 = client.post(f"{API}/whatsapp/inbound", json={"from_mobile": "9800000001", "text": "दवा कब देनी है?"}).json()
    assert "Iron syrup" in q2["replies"][0]["text"]

    # screen 21: dashboard + Hindi question
    o = login(client, "official")
    dash = client.get(f"{API}/dashboard/summary", headers=o).json()
    assert dash["kpis"]["children_screened"] > 100 and len(dash["coverage_by_block"]) == 6
    ans = client.post(f"{API}/dashboard/ask", headers=o, json={"q": "किस ब्लॉक में सबसे ज़्यादा बच्चे छूटे?"}).json()
    assert "ब्लॉक" in ans["answer"]


def test_decline_and_reroute(client):
    f = login(client, "facility")
    seema = next(i for i in client.get(f"{API}/facility/inbox", headers=f).json()["items"] if i["child_name"] == "सीमा")
    out = client.post(f"{API}/facility/referrals/{seema['id']}/decline", headers=f,
                      json={"reason": "wrong_facility", "reroute_to": "DH", "note": "orthopaedics OPD"}).json()
    assert out["status"] == "DECLINED" and out["rerouted_referral_id"] and out["training_case"]


def test_offline_sync_idempotent_and_provisional_change(client):
    w = login(client, "worker")
    priya = next(i for i in client.get(f"{API}/worker/home", headers=w).json()["items"] if i["child"]["name"] == "प्रिया")
    vid = str(uuid.uuid4())
    payload = {"visits": [{
        "id": vid, "child_id": priya["child"]["id"], "due_item_id": priya["due_item_id"], "mode": "offline",
        "consent": {"items": {"photo": True, "video": False, "voice": True, "whatsapp": True}, "method": "otp"},
        "domains": {"D1": {}, "D2": {"readings": {"muac_cm": 12.0, "hb_gdl": 10.5}}, "D3": {}, "D4": {"tasks": {"name_call_trials": 2, "name_call_turns": 2}}},
        "submit": {"worried": False}, "provisional_color": "GREEN"}]}
    r1 = client.post(f"{API}/sync/visits", headers=w, json=payload).json()["results"][0]
    assert r1["status"] == "synced" and r1["final"] == "YELLOW" and r1["changed_from_provisional"]
    r2 = client.post(f"{API}/sync/visits", headers=w, json=payload).json()["results"][0]
    assert r2["status"] == "already_synced"


def test_consent_refused_records_nothing(client):
    w = login(client, "worker")
    rahul = next(i for i in client.get(f"{API}/worker/home", headers=w).json()["items"] if i["child"]["name"] == "राहुल")
    vid = client.post(f"{API}/visits", json={"child_id": rahul["child"]["id"]}, headers=w).json()["visit_id"]
    assert client.put(f"{API}/visits/{vid}/consent", headers=w, json={"refused_reason": "पिता घर पर नहीं"}).json()["status"] == "refused"
    assert client.put(f"{API}/visits/{vid}/domains/D1", headers=w, json={}).status_code == 409


def test_register_with_duplicate_check(client):
    w = login(client, "worker")
    aman_id = client.get(f"{API}/children/search", params={"q": "अमन"}, headers=w).json()[0]["id"]
    dob = client.get(f"{API}/children/{aman_id}/card", headers=w).json()["child"]["dob"]
    body = {"name": "अमन", "gender": "M", "dob": dob,
            "mother_name": "रीना देवी", "parent_mobile": "9800000001"}
    assert client.post(f"{API}/children", headers=w, json=body).status_code == 409
    new = client.post(f"{API}/children", headers=w, json={**body, "name": "चिंटू", "mother_name": "कविता",
                                                          "parent_mobile": "9811111111", "dob": "2025-01-10"})
    assert new.status_code == 201 and new.json()["child"]["qr_code"].startswith("SB4D-")


def test_referral_ladder(client):
    o = login(client, "official")
    assert "actions" in client.post(f"{API}/jobs/referral-ladder", headers=o).json()


def test_followup_only_opens_one_d_and_parent_otp_consent(client):
    w = login(client, "worker")
    # register a fresh child, give it a D2-only follow-up due item via the yellow path of another visit is long;
    # instead use the seeded follow-up semantics: a visit tied to a D2-only due item
    from app.db import SessionLocal
    from app.models import DueItem, Child
    from datetime import date
    with SessionLocal() as db:
        c = db.query(Child).filter(Child.name == "राहुल").one()
        it = DueItem(child_id=c.id, worker_id=1, due_date=date.today(), kind="followup", label_hi="x", domains=["D2"])
        db.add(it); db.commit(); due_id, cid = it.id, c.id
    vid = str(uuid.uuid4())
    client.post(f"{API}/visits", json={"id": vid, "child_id": cid, "due_item_id": due_id}, headers=w)
    otp = client.post(f"{API}/visits/{vid}/consent/otp", headers=w).json()["dev_otp"]
    assert client.put(f"{API}/visits/{vid}/consent", headers=w, json={"items": {"photo": True}, "method": "otp", "otp": "000000"}).status_code == 400
    assert client.put(f"{API}/visits/{vid}/consent", headers=w, json={"items": {"photo": True}, "method": "otp", "otp": otp}).status_code == 200
    client.put(f"{API}/visits/{vid}/domains/D2", headers=w, json={"readings": {"hb_gdl": 12.0, "muac_cm": 13.4}})
    res = client.post(f"{API}/visits/{vid}/submit", headers=w, json={}).json()
    assert [d["domain"] for d in res["domains"]] == ["D2"] and res["final"] == "GREEN"
