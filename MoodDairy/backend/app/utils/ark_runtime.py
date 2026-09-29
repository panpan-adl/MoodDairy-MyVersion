"""Ark/OpenAI runtime configuration helpers."""

from __future__ import annotations

import os

import httpx
from dotenv import load_dotenv

load_dotenv()

DEFAULT_ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"


def _is_true(value: str) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def get_ark_base_url() -> str:
    """Return the OpenAI-compatible Ark base URL."""

    base_url = os.getenv("ARK_BASE_URL", DEFAULT_ARK_BASE_URL).strip()
    return base_url.rstrip("/")


def should_trust_env_proxy() -> bool:
    """Whether Ark clients should honor HTTP(S)_PROXY from the environment."""

    return _is_true(os.getenv("ARK_TRUST_ENV_PROXY", "0"))


def build_http_client(timeout: float = 60.0) -> httpx.Client:
    """Build a sync httpx client for Ark/OpenAI SDK usage."""

    return httpx.Client(timeout=timeout, trust_env=should_trust_env_proxy())


def build_async_http_client(timeout: float = 90.0) -> httpx.AsyncClient:
    """Build an async httpx client for direct streaming calls to Ark/OpenAI API."""

    return httpx.AsyncClient(
        timeout=httpx.Timeout(timeout),
        trust_env=should_trust_env_proxy(),
    )
