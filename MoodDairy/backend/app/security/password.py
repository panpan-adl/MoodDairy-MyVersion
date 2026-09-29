"""Password hashing with bcrypt."""

from passlib.context import CryptContext

_pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_password(plain_password: str) -> str:
    return _pwd_context.hash(plain_password)


def verify_password(plain_password: str, stored_password: str) -> bool:
    """Verify against bcrypt hash; fall back to plaintext match for legacy rows."""
    if not stored_password:
        return False
    if stored_password.startswith("$2"):
        return _pwd_context.verify(plain_password, stored_password)
    return plain_password == stored_password


def needs_rehash(stored_password: str) -> bool:
    if not stored_password or not stored_password.startswith("$2"):
        return True
    return _pwd_context.needs_update(stored_password)
