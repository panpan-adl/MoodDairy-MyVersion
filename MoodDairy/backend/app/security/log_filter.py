"""Redact sensitive substrings from log records."""

from __future__ import annotations

import logging
import re

_REDACT_PATTERNS = (
    (re.compile(r"(password['\"]?\s*[:=]\s*['\"]?)([^\s'\",}]+)", re.I), r"\1***"),
    (re.compile(r"(Bearer\s+)([A-Za-z0-9._-]+)", re.I), r"\1***"),
    (re.compile(r"\b1[3-9]\d{9}\b"), "[PHONE]"),
)


class SensitiveLogFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        # uvicorn.access 使用结构化 args，改写 msg/args 会导致 formatMessage 崩溃
        if record.name == "uvicorn.access":
            return True
        try:
            original = record.getMessage()
            message = original
            for pattern, repl in _REDACT_PATTERNS:
                message = pattern.sub(repl, message)
            if message != original:
                record.msg = message
                record.args = ()
        except Exception:
            pass
        return True


def install_sensitive_log_filter() -> None:
    filt = SensitiveLogFilter()
    logging.getLogger().addFilter(filt)
    for name in ("uvicorn", "sqlalchemy.engine"):
        logging.getLogger(name).addFilter(filt)
