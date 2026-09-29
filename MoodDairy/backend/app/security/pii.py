"""PII redaction before sending text to external LLM APIs."""

from __future__ import annotations

import re
from typing import Tuple

# Order matters: longer patterns first
_PII_PATTERNS: Tuple[Tuple[str, str], ...] = (
    (r"\b1[3-9]\d{9}\b", "[PHONE]"),
    (r"\b[\w.-]+@[\w.-]+\.\w+\b", "[EMAIL]"),
    (r"\b\d{17}[\dXx]\b", "[ID_CARD]"),
    (r"\b\d{6}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]\b", "[ID_CARD]"),
    (
        r"([\u4e00-\u9fff]{2,4})(?=说|表示|告诉|提到|觉得|认为|感到)",
        "[PERSON]",
    ),
)


def redact_pii(text: str) -> str:
    """Replace common PII patterns with placeholders."""
    if not text:
        return text
    result = text
    for pattern, replacement in _PII_PATTERNS:
        result = re.sub(pattern, replacement, result)
    return result


def sanitize_for_llm(text: str, max_length: int = 12000) -> str:
    """Redact PII and truncate for LLM context."""
    cleaned = redact_pii(text.strip())
    if len(cleaned) > max_length:
        return cleaned[:max_length] + "\n...[truncated]"
    return cleaned
