from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, Field

Color = Literal["GREEN", "YELLOW", "INCOMPLETE", "RED"]
Answer = Literal["yes", "no", "unsure"]


class OtpRequest(BaseModel):
    mobile: str = Field(pattern=r"^\d{10}$")


class OtpVerify(OtpRequest):
    otp: str = Field(pattern=r"^\d{6}$")


class PinSet(BaseModel):
    pin: str = Field(pattern=r"^\d{4}$")


class PinLogin(OtpRequest):
    pin: str = Field(pattern=r"^\d{4}$")


class UserOut(BaseModel):
    id: int
    name: str
    role: str
    worker_type: str | None = None
    mobile: str
    subtitle_hi: str | None = None
    facility_id: int | None = None


class TokenOut(BaseModel):
    token: str
    user: UserOut
    pin_set: bool


class AbsentIn(BaseModel):
    reason: Literal["not_home", "not_at_site", "unwell", "family_away", "other"]
    note: str | None = None


class ChildIn(BaseModel):
    name: str
    gender: Literal["M", "F", "O"]
    dob: date
    mother_name: str | None = None
    father_name: str | None = None
    parent_mobile: str | None = Field(default=None, pattern=r"^\d{10}$")
    village_id: int | None = None
    awc: str | None = None
    rbsk_id: str | None = None
    abha: str | None = None
    rch_id: str | None = None
    force: bool = False  # register even if possible duplicates were shown


class AlreadyScreenedIn(BaseModel):
    when: date
    where: str
    by_whom: str


class VisitCreate(BaseModel):
    id: str | None = None  # client-generated UUID makes sync idempotent
    child_id: int
    due_item_id: int | None = None
    mode: Literal["online", "offline"] = "online"


class ConsentIn(BaseModel):
    items: dict[str, bool] = Field(default_factory=dict)  # photo, video, voice, whatsapp
    method: Literal["otp", "voice"] | None = None
    otp: str | None = None
    voice_transcript: str | None = None
    refused_reason: str | None = None


class DangerIn(BaseModel):
    answers: dict[str, bool]


class DomainIn(BaseModel):
    answers: dict[str, Answer] = Field(default_factory=dict)
    readings: dict[str, float] = Field(default_factory=dict)
    tasks: dict[str, float | bool | int] = Field(default_factory=dict)
    comment: str | None = None
    worried: bool = False
    skipped_reason: str | None = None


class SubmitIn(BaseModel):
    worried: bool = False
    worried_reason: str | None = None
    comment: str | None = None


class FullVisitIn(VisitCreate):
    """Offline outbox item: one complete visit, uploaded in a single idempotent call."""
    started_at: datetime | None = None
    consent: ConsentIn | None = None
    danger_signs: dict[str, bool] = Field(default_factory=dict)
    domains: dict[str, DomainIn] = Field(default_factory=dict)
    submit: SubmitIn | None = None
    provisional_color: Color | None = None


class SyncIn(BaseModel):
    visits: list[FullVisitIn]


class AcceptIn(BaseModel):
    slot: datetime


class DeclineIn(BaseModel):
    reason: Literal["not_enough_evidence", "not_indicated", "wrong_facility", "already_treated", "duplicate"]
    note: str | None = None
    reroute_to: str | None = None  # facility type for wrong_facility


class CheckinIn(BaseModel):
    qr_code: str


class OutcomeIn(BaseModel):
    diagnosis_code: str
    diagnosis_text: str
    confirmed: bool = True
    findings: str | None = None
    treatment: str | None = None
    medicine: str | None = None
    comment: str | None = None
    next_followup: date | None = None


class AskIn(BaseModel):
    q: str


class WhatsAppIn(BaseModel):
    """Simplified inbound message (the real Cloud API webhook is mapped onto this)."""
    from_mobile: str
    text: str | None = None
    child_id: int | None = None  # from reply-to context or confirmation button
    media_kind: Literal["photo", "video", "audio", "document"] | None = None
