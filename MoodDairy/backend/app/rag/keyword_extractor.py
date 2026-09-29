"""Keyword extraction for RAG retrieval."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from typing import List, Tuple

from app.utils.ark_runtime import build_http_client, get_ark_base_url

logger = logging.getLogger(__name__)

KEYWORD_MODEL = os.getenv(
    "ARK_KEYWORD_MODEL",
    os.getenv("ARK_MODEL_NAME", "doubao-seed-1-8-251228"),
)


class KeywordExtractor:
    """Extract Chinese and English search keywords."""

    def __init__(self, api_key: str | None = None):
        self.api_key = api_key or os.getenv("ARK_API_KEY")
        self.base_url = get_ark_base_url()
        self._client = None

        if not self.api_key:
            logger.warning("ARK_API_KEY is not configured; keyword extraction will use fallback mode")

        logger.info("KeywordExtractor initialized: model=%s base_url=%s", KEYWORD_MODEL, self.base_url)

    @property
    def client(self):
        if self._client is None:
            from openai import OpenAI

            self._client = OpenAI(
                base_url=self.base_url,
                api_key=self.api_key,
                http_client=build_http_client(timeout=45.0),
            )
        return self._client

    async def extract_keywords(self, query: str) -> Tuple[List[str], List[str]]:
        """Return (chinese_keywords, english_keywords)."""

        if not self.api_key:
            return self._simple_extract(query)

        prompt = (
            "请从以下用户问题中提取用于知识库检索的关键词。\n"
            "要求：\n"
            "1. 提取 3-5 个核心中文关键词\n"
            "2. 给出对应英文关键词\n"
            "3. 尽量保留名词、动词或专业术语\n"
            "4. 避免提取“的”“是”“什么”等无意义词\n\n"
            f"用户问题：{query}\n\n"
            '请只返回 JSON，例如：{"chinese":["关键词1"],"english":["keyword1"]}'
        )

        try:
            loop = asyncio.get_running_loop()
            response = await loop.run_in_executor(
                None,
                lambda: self.client.chat.completions.create(
                    model=KEYWORD_MODEL,
                    messages=[
                        {
                            "role": "system",
                            "content": "你是一个关键词提取助手，只返回 JSON。",
                        },
                        {"role": "user", "content": prompt},
                    ],
                    temperature=0.3,
                    max_tokens=200,
                ),
            )

            content = response.choices[0].message.content if response.choices else ""
            match = re.search(r"\{.*\}", content or "", re.S)
            if not match:
                logger.warning("Keyword extractor returned non-JSON content: %s", content)
                return self._simple_extract(query)

            result = json.loads(match.group())
            chinese = result.get("chinese", []) or []
            english = result.get("english", []) or []
            logger.info("Keyword extraction succeeded: zh=%s en=%s", chinese, english)
            return chinese, english
        except Exception as exc:
            logger.error("Keyword extraction failed: %s", exc)
            return self._simple_extract(query)

    def _simple_extract(self, query: str) -> Tuple[List[str], List[str]]:
        """Fallback keyword extraction without an LLM call."""

        clean_query = re.sub(r"[^\w\s]", " ", query)
        words = clean_query.split()
        chinese = [word for word in words if len(word) >= 2 and any("\u4e00" <= ch <= "\u9fff" for ch in word)]

        if not chinese:
            chinese = [query[:20]] if len(query) > 20 else [query]

        logger.info("Keyword extraction fallback used: %s", chinese)
        return chinese[:5], []
