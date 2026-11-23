import hashlib
import hmac
import os
import secrets
from datetime import datetime, timedelta

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from sqlalchemy.orm import Session

from .config import get_settings
from .db import get_db
from .models import AuditLog, User

_bearer = HTTPBearer(auto_error=False)


def hash_secret(value: str, salt: bytes | None = None) -> str:
    salt = salt or os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", value.encode(), salt, 120_000)
    return f"{salt.hex()}${digest.hex()}"


def verify_secret(value: str, stored: str | None) -> bool:
    if not stored or "$" not in stored:
        return False
    salt_hex, _ = stored.split("$", 1)
    return hmac.compare_digest(hash_secret(value, bytes.fromhex(salt_hex)), stored)


def new_otp() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def create_token(user: User) -> str:
    s = get_settings()
    payload = {
        "sub": str(user.id),
        "role": user.role,
        "exp": datetime.utcnow() + timedelta(minutes=s.access_token_minutes),
    }
    return jwt.encode(payload, s.jwt_secret, algorithm=s.jwt_algorithm)


def current_user(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer), db: Session = Depends(get_db)
) -> User:
    if creds is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not authenticated")
    s = get_settings()
    try:
        payload = jwt.decode(creds.credentials, s.jwt_secret, algorithms=[s.jwt_algorithm])
    except JWTError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or expired token")
    user = db.get(User, int(payload["sub"]))
    if not user or not user.active:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "User blocked or missing")
    return user


def require_role(*roles: str):
    def checker(user: User = Depends(current_user)) -> User:
        if user.role not in roles:
            raise HTTPException(status.HTTP_403_FORBIDDEN, f"Requires role: {', '.join(roles)}")
        return user

    return checker


def audit(db: Session, user: User | None, action: str, entity: str, entity_id, **detail) -> None:
    db.add(AuditLog(user_id=user.id if user else None, action=action, entity=entity,
                    entity_id=str(entity_id), detail=detail))
