"""Database models.

Identity data (Child names, mobile) and health data (Visit/DomainResult/Media) are kept in
separate tables keyed by the child token, mirroring the identity-vault / health-store split
in the design (section 7.1 block 3). In production the two groups live in separate
PostgreSQL databases with pgcrypto + row-level security.
"""
import uuid
from datetime import date, datetime

from sqlalchemy import JSON, Boolean, Date, DateTime, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


def _uuid() -> str:
    return str(uuid.uuid4())


def _now() -> datetime:
    return datetime.utcnow()


# ---------------------------------------------------------------- geography / org
class Area(Base):
    __tablename__ = "areas"
    id: Mapped[int] = mapped_column(primary_key=True)
    lgd_code: Mapped[str] = mapped_column(String(20), unique=True)
    name: Mapped[str] = mapped_column(String(120))
    name_hi: Mapped[str | None] = mapped_column(String(120))
    level: Mapped[str] = mapped_column(String(20))  # state | district | block | village
    parent_id: Mapped[int | None] = mapped_column(ForeignKey("areas.id"))


class Facility(Base):
    __tablename__ = "facilities"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(160))
    name_hi: Mapped[str | None] = mapped_column(String(160))
    type: Mapped[str] = mapped_column(String(20))  # DEIC | NRC | PHC | CHC | DH | NCD | AFHC | DMHP
    area_id: Mapped[int] = mapped_column(ForeignKey("areas.id"))
    hfr_id: Mapped[str | None] = mapped_column(String(40))
    opd_slots: Mapped[list] = mapped_column(JSON, default=list)  # ["10:30", "11:00"]


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    mobile: Mapped[str] = mapped_column(String(15), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(120))
    role: Mapped[str] = mapped_column(String(20))  # worker | facility | official | admin
    worker_type: Mapped[str | None] = mapped_column(String(20))  # ASHA | AWW | ANM | MHT | DP
    area_id: Mapped[int | None] = mapped_column(ForeignKey("areas.id"))  # block (worker) / official's area
    village_id: Mapped[int | None] = mapped_column(ForeignKey("areas.id"))  # worker catchment
    facility_id: Mapped[int | None] = mapped_column(ForeignKey("facilities.id"))
    hpr_id: Mapped[str | None] = mapped_column(String(40))
    pin_hash: Mapped[str | None] = mapped_column(String(200))
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=_now)


class OtpCode(Base):
    __tablename__ = "otp_codes"
    id: Mapped[int] = mapped_column(primary_key=True)
    mobile: Mapped[str] = mapped_column(String(15), index=True)
    code_hash: Mapped[str] = mapped_column(String(200))
    expires_at: Mapped[datetime] = mapped_column(DateTime)
    used: Mapped[bool] = mapped_column(Boolean, default=False)


# ---------------------------------------------------------------- identity vault
class Child(Base):
    __tablename__ = "children"
    id: Mapped[int] = mapped_column(primary_key=True)
    token: Mapped[str] = mapped_column(String(36), unique=True, default=_uuid, index=True)
    qr_code: Mapped[str] = mapped_column(String(40), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(120))
    gender: Mapped[str] = mapped_column(String(1))  # M | F | O
    dob: Mapped[date] = mapped_column(Date)
    mother_name: Mapped[str | None] = mapped_column(String(120))
    father_name: Mapped[str | None] = mapped_column(String(120))
    parent_mobile: Mapped[str | None] = mapped_column(String(15), index=True)
    village_id: Mapped[int | None] = mapped_column(ForeignKey("areas.id"))
    block_id: Mapped[int | None] = mapped_column(ForeignKey("areas.id"))
    awc: Mapped[str | None] = mapped_column(String(120))
    rbsk_id: Mapped[str | None] = mapped_column(String(40), index=True)
    abha: Mapped[str | None] = mapped_column(String(40), index=True)
    rch_id: Mapped[str | None] = mapped_column(String(40), index=True)
    source: Mapped[str] = mapped_column(String(20), default="field")  # JANANI | UWIN | POSHAN | CSV | field
    whatsapp_opt_in: Mapped[bool] = mapped_column(Boolean, default=False)
    care_status: Mapped[str] = mapped_column(String(20), default="none")  # none | home_plan | under_referral
    last_screened_at: Mapped[datetime | None] = mapped_column(DateTime)
    last_screened_where: Mapped[str | None] = mapped_column(String(120))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=_now)


# ---------------------------------------------------------------- schedule
class DueItem(Base):
    __tablename__ = "due_items"
    id: Mapped[int] = mapped_column(primary_key=True)
    child_id: Mapped[int] = mapped_column(ForeignKey("children.id"), index=True)
    worker_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), index=True)
    due_date: Mapped[date] = mapped_column(Date)
    due_time: Mapped[str | None] = mapped_column(String(5))
    kind: Mapped[str] = mapped_column(String(20))  # missed | newborn | followup | overdue | routine | urgent
    label_hi: Mapped[str] = mapped_column(String(160))
    domains: Mapped[list] = mapped_column(JSON, default=lambda: ["D1", "D2", "D3", "D4"])
    status: Mapped[str] = mapped_column(String(20), default="due")  # due | done | absent | done_elsewhere
    absent_count: Mapped[int] = mapped_column(Integer, default=0)
    absent_reason: Mapped[str | None] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=_now)

    child: Mapped[Child] = relationship()


# ---------------------------------------------------------------- health store
class Visit(Base):
    __tablename__ = "visits"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)  # client UUID (idempotent)
    child_id: Mapped[int] = mapped_column(ForeignKey("children.id"), index=True)
    worker_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    due_item_id: Mapped[int | None] = mapped_column(ForeignKey("due_items.id"))
    unscheduled: Mapped[bool] = mapped_column(Boolean, default=False)
    started_at: Mapped[datetime] = mapped_column(DateTime, default=_now)
    consent: Mapped[dict] = mapped_column(JSON, default=dict)  # {photo, video, voice, whatsapp}
    consent_method: Mapped[str | None] = mapped_column(String(10))  # otp | voice
    consent_refused_reason: Mapped[str | None] = mapped_column(String(200))
    danger_signs: Mapped[dict] = mapped_column(JSON, default=dict)
    worried: Mapped[bool] = mapped_column(Boolean, default=False)
    worried_reason: Mapped[str | None] = mapped_column(Text)
    comment: Mapped[str | None] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(20), default="draft")  # draft | submitted | refused | emergency
    mode: Mapped[str] = mapped_column(String(10), default="online")  # online | offline
    final_color: Mapped[str | None] = mapped_column(String(12))
    provisional_color: Mapped[str | None] = mapped_column(String(12))
    result_is_final: Mapped[bool] = mapped_column(Boolean, default=False)
    result_seen_by_worker: Mapped[bool] = mapped_column(Boolean, default=False)
    rules_version: Mapped[str | None] = mapped_column(String(20))
    submitted_at: Mapped[datetime | None] = mapped_column(DateTime)

    domains: Mapped[list["DomainResult"]] = relationship(back_populates="visit", cascade="all, delete-orphan")
    child: Mapped[Child] = relationship()


class DomainResult(Base):
    __tablename__ = "domain_results"
    id: Mapped[int] = mapped_column(primary_key=True)
    visit_id: Mapped[str] = mapped_column(ForeignKey("visits.id"), index=True)
    domain: Mapped[str] = mapped_column(String(2))  # D1..D4
    answers: Mapped[dict] = mapped_column(JSON, default=dict)  # {question_id: yes|no|unsure}
    readings: Mapped[dict] = mapped_column(JSON, default=dict)  # {muac_cm: 12.1, hb_gdl: 9.8, ...}
    tasks: Mapped[dict] = mapped_column(JSON, default=dict)  # {name_call_turns: 0, name_call_trials: 2, ...}
    comment: Mapped[str | None] = mapped_column(Text)
    worried: Mapped[bool] = mapped_column(Boolean, default=False)
    skipped_reason: Mapped[str | None] = mapped_column(String(200))
    color: Mapped[str | None] = mapped_column(String(12))
    evidence: Mapped[list] = mapped_column(JSON, default=list)  # [{rule_id, color, text_hi, text_en, source}]

    visit: Mapped[Visit] = relationship(back_populates="domains")


class Media(Base):
    __tablename__ = "media"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    visit_id: Mapped[str] = mapped_column(ForeignKey("visits.id"), index=True)
    domain: Mapped[str] = mapped_column(String(2))
    kind: Mapped[str] = mapped_column(String(10))  # photo | video | audio
    tag: Mapped[str] = mapped_column(String(40))  # face_front, eyes_flash, name_call, ...
    path: Mapped[str] = mapped_column(String(300))
    sha256: Mapped[str] = mapped_column(String(64), index=True)
    size_bytes: Mapped[int] = mapped_column(Integer)
    evidence_code: Mapped[str] = mapped_column(String(10))  # E-07 style reference shown to doctors
    ai_findings: Mapped[list] = mapped_column(JSON, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=_now)


# ---------------------------------------------------------------- referral
class Referral(Base):
    __tablename__ = "referrals"
    id: Mapped[int] = mapped_column(primary_key=True)
    child_id: Mapped[int] = mapped_column(ForeignKey("children.id"), index=True)
    visit_id: Mapped[str] = mapped_column(ForeignKey("visits.id"))
    facility_id: Mapped[int] = mapped_column(ForeignKey("facilities.id"), index=True)
    domains: Mapped[list] = mapped_column(JSON, default=list)
    reason_hi: Mapped[str] = mapped_column(String(300))
    reason_en: Mapped[str] = mapped_column(String(300))
    urgency: Mapped[str] = mapped_column(String(10), default="routine")  # emergency | soon | routine
    status: Mapped[str] = mapped_column(String(20), default="CREATED")
    # CREATED -> NOTIFIED -> BOOKED -> REACHED -> SEEN -> OUTCOME_RECORDED -> CLOSED ; DECLINED
    qr_code: Mapped[str] = mapped_column(String(40), unique=True)
    slot: Mapped[datetime | None] = mapped_column(DateTime)
    due_by: Mapped[date | None] = mapped_column(Date)
    ai_summary: Mapped[dict] = mapped_column(JSON, default=dict)
    decline_reason: Mapped[str | None] = mapped_column(String(40))
    decline_note: Mapped[str | None] = mapped_column(Text)
    outcome: Mapped[dict | None] = mapped_column(JSON)
    escalation_level: Mapped[str | None] = mapped_column(String(10))  # block | district
    created_at: Mapped[datetime] = mapped_column(DateTime, default=_now)
    reached_at: Mapped[datetime | None] = mapped_column(DateTime)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime)

    child: Mapped[Child] = relationship()
    facility: Mapped[Facility] = relationship()


class HomePlan(Base):
    __tablename__ = "home_plans"
    id: Mapped[int] = mapped_column(primary_key=True)
    child_id: Mapped[int] = mapped_column(ForeignKey("children.id"), index=True)
    visit_id: Mapped[str] = mapped_column(ForeignKey("visits.id"))
    domain: Mapped[str] = mapped_column(String(2))
    focus: Mapped[str] = mapped_column(String(40))  # anaemia | speech | motor | growth ...
    recheck_date: Mapped[date] = mapped_column(Date)
    progress_score: Mapped[float] = mapped_column(Float, default=0)
    weekly_scores: Mapped[list] = mapped_column(JSON, default=list)
    status: Mapped[str] = mapped_column(String(20), default="ACTIVE")  # ACTIVE | IMPROVING | NOT_IMPROVING | TARGET_MET
    active: Mapped[bool] = mapped_column(Boolean, default=True)


class Message(Base):
    __tablename__ = "messages"
    id: Mapped[int] = mapped_column(primary_key=True)
    child_id: Mapped[int | None] = mapped_column(ForeignKey("children.id"), index=True)
    to_mobile: Mapped[str] = mapped_column(String(15), index=True)
    direction: Mapped[str] = mapped_column(String(3))  # out | in
    channel: Mapped[str] = mapped_column(String(10), default="whatsapp")  # whatsapp | sms | ivr
    template: Mapped[str | None] = mapped_column(String(60))
    body: Mapped[str] = mapped_column(Text)
    payload: Mapped[dict] = mapped_column(JSON, default=dict)
    delivered: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=_now)


class AuditLog(Base):
    __tablename__ = "audit_log"  # append-only
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int | None] = mapped_column(Integer)
    action: Mapped[str] = mapped_column(String(60))
    entity: Mapped[str] = mapped_column(String(40))
    entity_id: Mapped[str] = mapped_column(String(40))
    detail: Mapped[dict] = mapped_column(JSON, default=dict)
    at: Mapped[datetime] = mapped_column(DateTime, default=_now)
