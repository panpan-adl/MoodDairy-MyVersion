"""Application configuration."""

from __future__ import annotations

import logging
from typing import Optional

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

logger = logging.getLogger(__name__)


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    model_config = SettingsConfigDict(
        env_file=".env",
        case_sensitive=False,
        extra="allow",
    )

    database_url: str = "postgresql+asyncpg://app_user:CHANGE_ME@47.104.168.245:5432/app_db"
    upload_dir: str = "./uploads"

    oss_enabled: bool = False
    oss_fallback_to_local: bool = True
    oss_endpoint: Optional[str] = None
    oss_bucket: Optional[str] = None
    oss_access_key_id: Optional[str] = None
    oss_access_key_secret: Optional[str] = None
    oss_public_base_url: Optional[str] = None
    oss_prefix: str = "media/"

    rag_enabled: bool = True
    keyword_extraction_enabled: bool = True
    ark_model_name: Optional[str] = None

    # Social Search (TikHub)
    social_search_enabled: bool = False
    social_search_provider: str = "tikhub"
    tikhub_api_key: Optional[str] = None
    tikhub_base_url: str = "https://api.tikhub.dev"
    tikhub_timeout_seconds: float = 15.0
    social_search_default_limit: int = 8
    social_search_max_limit: int = 20
    social_search_cache_ttl_seconds: int = 300
    social_search_rate_limit_per_minute: int = 20

    @model_validator(mode="after")
    def validate_oss_config(self):
        """Validate OSS settings when OSS mode is enabled."""

        if not self.oss_enabled:
            logger.info("OSS is disabled; using local storage.")
            return self

        required_fields = [
            ("oss_endpoint", "OSS_ENDPOINT"),
            ("oss_bucket", "OSS_BUCKET"),
            ("oss_access_key_id", "OSS_ACCESS_KEY_ID"),
            ("oss_access_key_secret", "OSS_ACCESS_KEY_SECRET"),
            ("oss_public_base_url", "OSS_PUBLIC_BASE_URL"),
        ]

        missing_fields = [env_name for field_name, env_name in required_fields if not getattr(self, field_name)]
        if missing_fields:
            error_msg = f"OSS_ENABLED=true requires: {', '.join(missing_fields)}"
            logger.error(error_msg)
            raise ValueError(error_msg)

        logger.info("OSS configuration validated.")
        logger.info("OSS endpoint: %s", self.oss_endpoint)
        logger.info("OSS bucket: %s", self.oss_bucket)
        logger.info("OSS fallback to local: %s", self.oss_fallback_to_local)
        return self


settings = Settings()

logger.info("=" * 50)
logger.info("Application configuration loaded")
logger.info("OSS enabled: %s", settings.oss_enabled)
if settings.oss_enabled:
    logger.info("OSS endpoint: %s", settings.oss_endpoint)
    logger.info("OSS bucket: %s", settings.oss_bucket)
    logger.info("OSS fallback to local: %s", settings.oss_fallback_to_local)
else:
    logger.info("Local upload directory: %s", settings.upload_dir)
logger.info("RAG enabled: %s", settings.rag_enabled)
logger.info("Keyword extraction enabled: %s", settings.keyword_extraction_enabled)
logger.info("ARK model: %s", settings.ark_model_name)
logger.info("=" * 50)
