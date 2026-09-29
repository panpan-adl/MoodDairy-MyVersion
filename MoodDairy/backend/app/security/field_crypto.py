"""Field-level encryption for sensitive diary text (Fernet / AES)."""

from __future__ import annotations

import base64
import hashlib
import logging
import os
from typing import Optional

from cryptography.fernet import Fernet, InvalidToken

logger = logging.getLogger(__name__)

_ENC_PREFIX = "enc:v1:"
_enabled = os.getenv("FIELD_ENCRYPTION_ENABLED", "1").strip().lower() in {"1", "true", "yes"}
_raw_key = os.getenv("FIELD_ENCRYPTION_KEY", "")


def _build_fernet() -> Optional[Fernet]:
    if not _raw_key:
        seed = os.getenv("JWT_SECRET", "diary-default-key")
        derived = base64.urlsafe_b64encode(hashlib.sha256(seed.encode()).digest())
        return Fernet(derived)
    key = _raw_key.encode() if isinstance(_raw_key, str) else _raw_key
    if len(key) != 44:
        derived = base64.urlsafe_b64encode(hashlib.sha256(key).digest())
        return Fernet(derived)
    return Fernet(key)


_fernet = _build_fernet() if _enabled else None


def encrypt_field(value: Optional[str]) -> Optional[str]:
    if not value or not _fernet:
        return value
    if value.startswith(_ENC_PREFIX):
        return value
    token = _fernet.encrypt(value.encode("utf-8")).decode("ascii")
    return f"{_ENC_PREFIX}{token}"


def decrypt_field(value: Optional[str]) -> Optional[str]:
    if not value:
        return value
    if not value.startswith(_ENC_PREFIX):
        return value
    if not _fernet:
        logger.warning("Encrypted field present but FIELD_ENCRYPTION is disabled")
        return value
    token = value[len(_ENC_PREFIX) :]
    try:
        return _fernet.decrypt(token.encode("ascii")).decode("utf-8")
    except InvalidToken:
        logger.error("Failed to decrypt field; returning ciphertext placeholder")
        return "[decryption_failed]"


def decrypt_diary_fields(diary) -> None:
    """Decrypt title/content on a Diary ORM instance in place."""
    if diary is None:
        return
    diary.title = decrypt_field(diary.title)
    diary.content = decrypt_field(diary.content)


def encrypt_diary_fields(title: Optional[str], content: Optional[str]) -> tuple[Optional[str], Optional[str]]:
    return encrypt_field(title), encrypt_field(content)
