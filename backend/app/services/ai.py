"""Cloud AI adapter (design 8.1-8.5).

Two models are run on every photo / video keyframe in parallel. Both must agree to clear; one
alone may raise; disagreement keeps the higher-risk finding. Only the child token and the capture
tag go to the model - never the name.

Without SB4D_AI_BASE_URL configured the adapter returns no findings, so the result is decided by
rules + questionnaire + worker concern only (which is the safe default: AI can only raise).
"""
import base64
import json
import logging
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

from ..config import get_settings

log = logging.getLogger(__name__)

PROMPT = (
    "You are screening (not diagnosing) a child photo for the RBSK programme. Capture tag: {tag}. "
    "Domain: {domain}. Reply ONLY JSON: {{\"present\": bool, \"sign\": str, \"color\": \"YELLOW\"|\"RED\", "
    "\"quality_ok\": bool}}. Say present=false if unsure about the sign but image quality is fine."
)


def _call(model: str, image_path: str, tag: str, domain: str) -> dict | None:
    s = get_settings()
    b64 = base64.b64encode(Path(image_path).read_bytes()).decode()
    body = {
        "model": model,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": PROMPT.format(tag=tag, domain=domain)},
            {"type": "image_url", "image_url": {"url": f"data:image/webp;base64,{b64}"}},
        ]}],
    }
    try:
        r = httpx.post(f"{s.ai_base_url.rstrip('/')}/chat/completions", json=body, timeout=60,
                       headers={"Authorization": f"Bearer {s.ai_api_key or ''}"})
        r.raise_for_status()
        return json.loads(r.json()["choices"][0]["message"]["content"])
    except Exception as exc:  # noqa: BLE001 - AI outages must never block the visit
        log.warning("AI call %s failed: %s", model, exc)
        return None


def analyze_photo(image_path: str, tag: str, domain: str, evidence_code: str) -> list[dict]:
    s = get_settings()
    if not s.ai_base_url:
        return []
    models = [s.ai_model_primary, s.ai_model_secondary]
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda m: _call(m, image_path, tag, domain), models))
    findings = []
    for model, res in zip(models, results):
        if not res:
            continue
        if not res.get("quality_ok", True):
            findings.append({"model": model, "present": True, "color": "YELLOW", "sign": "poor quality - retake",
                             "text_hi": "फ़ोटो साफ़ नहीं - दोबारा लें", "evidence_ids": [evidence_code]})
        elif res.get("present"):
            findings.append({"model": model, "present": True, "color": res.get("color", "YELLOW"),
                             "sign": res.get("sign", ""), "text_hi": "AI ने संकेत देखा: " + res.get("sign", ""),
                             "evidence_ids": [evidence_code]})
    return findings


def models_agree(findings: list[dict]) -> bool:
    return len({f["model"] for f in findings}) != 1
