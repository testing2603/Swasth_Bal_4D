"""Schedule engine (design 4.1 / 9.3): RBSK cycles + follow-ups -> daily due list per worker."""
from datetime import date, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Child, DueItem, User

PRIORITY = {"urgent": 0, "missed": 1, "newborn": 2, "overdue": 3, "followup": 4, "routine": 5, "retake": 4}
BADGE_HI = {"urgent": "ज़रूरी", "missed": "छूटी", "newborn": "नवजात", "overdue": "देरी",
            "followup": "फॉलो-अप", "routine": "नियमित", "retake": "दोबारा"}
NEWBORN_DAYS = [3, 7, 14, 21, 28, 42]
INFANT_MONTHS = [3, 6, 9, 12, 15]


def age_months(dob: date, on: date | None = None) -> int:
    on = on or date.today()
    m = (on.year - dob.year) * 12 + (on.month - dob.month)
    if on.day < dob.day:
        m -= 1
    return max(m, 0)


def age_label_hi(dob: date, on: date | None = None) -> str:
    on = on or date.today()
    days = (on - dob).days
    if days < 42:
        return f"{days} दिन"
    m = age_months(dob, on)
    if m < 24:
        return f"{m} माह"
    y, rm = divmod(m, 12)
    return f"{y} वर्ष {rm} माह" if rm else f"{y} वर्ष"


def age_label_en(dob: date, on: date | None = None) -> str:
    on = on or date.today()
    days = (on - dob).days
    if days < 42:
        return f"{days} d"
    y, rm = divmod(age_months(dob, on), 12)
    return f"{y} y {rm} m" if y else f"{rm} m"


def next_cycle_date(dob: date, after: date) -> tuple[date, str, str]:
    """Return (due date, kind, Hindi label) of the next routine contact after `after`."""
    for d in NEWBORN_DAYS:
        due = dob + timedelta(days=d)
        if due > after:
            return due, "newborn", f"{d} दिन · नवजात घर-भ्रमण (दिन {d})"
    for m in INFANT_MONTHS:
        due = _add_months(dob, m)
        if due > after:
            return due, "routine", f"{m} माह · घर-भ्रमण"
    age = age_months(dob, after)
    if age < 72:
        return after + timedelta(days=182), "routine", "आँगनवाड़ी जाँच (6 माह)"
    return after + timedelta(days=365), "routine", "स्कूल जाँच (वार्षिक)"


def _add_months(d: date, months: int) -> date:
    y, m = divmod(d.month - 1 + months, 12)
    day = min(d.day, 28)
    return date(d.year + y, m + 1, day)


def worker_for_child(db: Session, child: Child) -> User | None:
    """Official catchment first (village -> ASHA)."""
    if child.village_id is None:
        return None
    return db.scalars(select(User).where(User.role == "worker", User.village_id == child.village_id,
                                         User.active.is_(True))).first()


def schedule(db: Session, child: Child, due: date, kind: str, label_hi: str,
             domains: list[str] | None = None, due_time: str | None = None) -> DueItem:
    w = worker_for_child(db, child)
    item = DueItem(child_id=child.id, worker_id=w.id if w else None, due_date=due, due_time=due_time,
                   kind=kind, label_hi=label_hi, domains=domains or ["D1", "D2", "D3", "D4"])
    db.add(item)
    return item


def schedule_next_routine(db: Session, child: Child, after: date | None = None) -> DueItem:
    after = after or date.today()
    due, kind, label = next_cycle_date(child.dob, after)
    return schedule(db, child, due, kind, label)


def due_list(db: Session, worker: User, on: date | None = None) -> list[DueItem]:
    on = on or date.today()
    items = db.scalars(select(DueItem).where(DueItem.worker_id == worker.id, DueItem.status == "due",
                                             DueItem.due_date <= on)).all()
    for it in items:  # overdue promotion
        if it.kind in ("routine", "newborn") and it.due_date < on - timedelta(days=7):
            it.kind = "overdue"
    return sorted(items, key=lambda i: (PRIORITY.get(i.kind, 9), i.due_date))


def mark_absent(db: Session, item: DueItem, reason: str) -> tuple[DueItem, bool]:
    """Child not present -> rebook tomorrow 10:00. Returns (new item, escalate_to_supervisor)."""
    item.status = "absent"
    item.absent_reason = reason
    count = item.absent_count + 1
    tomorrow = date.today() + timedelta(days=1)
    new = DueItem(child_id=item.child_id, worker_id=item.worker_id, due_date=tomorrow, due_time="10:00",
                  kind="missed", label_hi=f"{item.label_hi.split(' · ')[0]} · पिछली बार अनुपस्थित",
                  domains=item.domains, absent_count=count)
    db.add(new)
    return new, count >= 3


def close_due_for_child(db: Session, child_id: int, done_by_worker_id: int) -> None:
    for it in db.scalars(select(DueItem).where(DueItem.child_id == child_id, DueItem.status == "due")).all():
        it.status = "done" if it.worker_id in (None, done_by_worker_id) else "done_elsewhere"


def when_label_hi(d: date, t: str | None) -> str:
    months = ["जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"]
    prefix = "कल, " if d == date.today() + timedelta(days=1) else ""
    s = f"{prefix}{d.day} {months[d.month - 1]}"
    if t:
        h = int(t.split(":")[0])
        part = "सुबह" if h < 12 else ("दोपहर" if h < 16 else "शाम")
        s += f" · {part} {h if h <= 12 else h - 12} बजे"
    return s


def now() -> datetime:
    return datetime.utcnow()
