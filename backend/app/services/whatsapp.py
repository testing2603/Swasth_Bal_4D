"""Parent channel (design section 10). Every outbound message is stored; if WhatsApp Cloud API
credentials are configured it is also sent. Parents never log in - the number linked at
registration is the identity, and every message is tagged to one child."""
import logging

import httpx
from sqlalchemy.orm import Session

from ..config import get_settings
from ..models import Child, Message

log = logging.getLogger(__name__)
EMOJI = {"GREEN": "🟢", "YELLOW": "🟡", "INCOMPLETE": "⚪", "RED": "🔴"}
COLOR_HI = {"GREEN": "ठीक", "YELLOW": "ध्यान दें", "INCOMPLETE": "दोबारा जाँच", "RED": "डॉक्टर को दिखाएँ"}

MENU = [
    ("1", "गतिविधि भेजें"),
    ("2", "सवाल पूछें"),
    ("3", "पर्चा / रिपोर्ट भेजें"),
    ("4", "स्थिति देखें"),
    ("5", "आशा दीदी से बात करें"),
]


def child_tag(child: Child) -> str:
    from .schedule import age_months
    return f"{child.name.split()[0]} ({age_months(child.dob) // 12 or age_months(child.dob)})"


def send(db: Session, child: Child | None, body: str, template: str | None = None,
         payload: dict | None = None, channel: str | None = None, to: str | None = None) -> Message:
    to = to or (child.parent_mobile if child else None) or ""
    if channel is None:
        channel = "whatsapp" if (child is None or child.whatsapp_opt_in) else "sms"
    msg = Message(child_id=child.id if child else None, to_mobile=to, direction="out", channel=channel,
                  template=template, body=body, payload=payload or {})
    db.add(msg)
    s = get_settings()
    if channel == "whatsapp" and s.wa_token and s.wa_phone_number_id and to:
        try:
            r = httpx.post(
                f"https://graph.facebook.com/v20.0/{s.wa_phone_number_id}/messages",
                headers={"Authorization": f"Bearer {s.wa_token}"},
                json={"messaging_product": "whatsapp", "to": "91" + to[-10:], "type": "text",
                      "text": {"body": body}},
                timeout=10,
            )
            msg.delivered = r.status_code < 300
        except httpx.HTTPError as exc:  # never block field work on the channel
            log.warning("WhatsApp send failed: %s", exc)
    else:
        msg.delivered = True  # simulated delivery in dev
    return msg


def menu_payload(child: Child) -> dict:
    return {"type": "list", "title": f"{child_tag(child)} के लिए क्या करना है?",
            "rows": [{"id": k, "title": v} for k, v in MENU]}
