from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..config import get_settings
from ..db import get_db
from ..models import Area, Facility, OtpCode, User
from ..schemas import OtpRequest, OtpVerify, PinLogin, PinSet, TokenOut, UserOut
from ..security import create_token, current_user, hash_secret, new_otp, verify_secret

router = APIRouter(prefix="/auth", tags=["auth"])


def user_out(db: Session, u: User) -> UserOut:
    sub = None
    if u.role == "worker":
        village = db.get(Area, u.village_id) if u.village_id else None
        block = db.get(Area, u.area_id) if u.area_id else None
        wt = {"ASHA": "आशा", "AWW": "आँगनवाड़ी", "ANM": "ए.एन.एम.", "MHT": "मोबाइल हेल्थ टीम"}.get(u.worker_type or "", "")
        sub = f"{wt} · गाँव {village.name_hi if village else ''} · ब्लॉक {block.name_hi if block else ''}"
    elif u.role == "facility" and u.facility_id:
        f = db.get(Facility, u.facility_id)
        sub = f"{f.type} · {f.name_hi or f.name} · लॉगिन: {u.name}"
    elif u.role == "official" and u.area_id:
        a = db.get(Area, u.area_id)
        sub = f"{a.level.title()} · {a.name}"
    return UserOut(id=u.id, name=u.name, role=u.role, worker_type=u.worker_type, mobile=u.mobile,
                   subtitle_hi=sub, facility_id=u.facility_id)


@router.post("/otp/request")
def request_otp(body: OtpRequest, db: Session = Depends(get_db)):
    user = db.scalars(select(User).where(User.mobile == body.mobile)).first()
    if not user or not user.active:
        # numbers are pre-registered by the block / facility admin (design 2.1)
        raise HTTPException(404, "यह नंबर पंजीकृत नहीं है - ब्लॉक एडमिन से संपर्क करें")
    code = new_otp()
    db.add(OtpCode(mobile=body.mobile, code_hash=hash_secret(code),
                   expires_at=datetime.utcnow() + timedelta(seconds=get_settings().otp_ttl_seconds)))
    db.commit()
    # production: send through the SMS gateway here
    return {"sent": True, **({"dev_otp": code} if get_settings().dev_mode else {})}


@router.post("/otp/verify", response_model=TokenOut)
def verify_otp(body: OtpVerify, db: Session = Depends(get_db)):
    rows = db.scalars(select(OtpCode).where(OtpCode.mobile == body.mobile, OtpCode.used.is_(False),
                                            OtpCode.expires_at > datetime.utcnow())
                      .order_by(OtpCode.id.desc())).all()
    match = next((r for r in rows if verify_secret(body.otp, r.code_hash)), None)
    if not match:
        raise HTTPException(400, "OTP गलत या समय समाप्त")
    match.used = True
    user = db.scalars(select(User).where(User.mobile == body.mobile)).one()
    db.commit()
    return TokenOut(token=create_token(user), user=user_out(db, user), pin_set=bool(user.pin_hash))


@router.post("/pin/set")
def set_pin(body: PinSet, user: User = Depends(current_user), db: Session = Depends(get_db)):
    user.pin_hash = hash_secret(body.pin)
    db.commit()
    return {"ok": True}


@router.post("/pin/login", response_model=TokenOut)
def pin_login(body: PinLogin, db: Session = Depends(get_db)):
    user = db.scalars(select(User).where(User.mobile == body.mobile)).first()
    if not user or not verify_secret(body.pin, user.pin_hash):
        raise HTTPException(400, "PIN गलत है")
    return TokenOut(token=create_token(user), user=user_out(db, user), pin_set=True)


@router.get("/me", response_model=UserOut)
def me(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return user_out(db, user)
