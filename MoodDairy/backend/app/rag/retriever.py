"""RAG retrieval with vector search and keyword fallback."""

from __future__ import annotations

import logging
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .document_loader import DocumentLoader
from .embedding_service import EmbeddingService, get_embedding_service
from .faiss_store import FAISSStore, get_faiss_store
from .keyword_extractor import KeywordExtractor
from app.config import settings

logger = logging.getLogger(__name__)


def _env_true(name: str, default: str = "1") -> bool:
    return os.getenv(name, default).strip().lower() in {"1", "true", "yes", "on"}


class RAGRetriever:
    """RAG retriever with automatic knowledge loading and fallback search."""

    def __init__(
        self,
        embedding_service: EmbeddingService | None = None,
        faiss_store: FAISSStore | None = None,
        keyword_extractor: KeywordExtractor | None = None,
    ):
        self.embedding_service = embedding_service or get_embedding_service()
        self.faiss_store = faiss_store or get_faiss_store()
        self.keyword_extractor = keyword_extractor or KeywordExtractor()

        self._cache: Dict[str, List[Dict[str, Any]]] = {}
        self._cache_max_size = 100
        self._knowledge_bootstrapped = False

        logger.info("RAGRetriever initialized")

    async def retrieve(
        self,
        query: str,
        top_k: int = 5,
        use_keywords: bool = True,
        min_score: float = 0.1,
    ) -> List[Dict[str, Any]]:
        """Retrieve relevant knowledge documents."""

        cache_key = f"{query}:{top_k}:{use_keywords}:{min_score}"
        if cache_key in self._cache:
            logger.info("Using cached RAG result")
            return self._cache[cache_key]

        if self.faiss_store.count == 0:
            await self._ensure_knowledge_loaded()

        if self.faiss_store.count == 0:
            logger.warning("Knowledge base is empty; retrieval skipped")
            return []

        all_results: List[Tuple[Dict[str, Any], float]] = []
        search_queries = [query]
        use_embedding = _env_true("RAG_ENABLE_EMBEDDING", "1")

        if settings.keyword_extraction_enabled:
            try:
                chinese_keywords, english_keywords = await self.keyword_extractor.extract_keywords(query)
                search_queries.extend(chinese_keywords[:3])
                search_queries.extend(english_keywords[:3])
            except Exception as exc:
                logger.warning("Keyword extraction failed; using raw query only: %s", exc)

        if use_embedding:
            try:
                if use_keywords:
                    embeddings = await self.embedding_service.embed_texts(search_queries, batch_size=5)
                    for index, embedding in enumerate(embeddings):
                        if not embedding or all(value == 0.0 for value in embedding):
                            continue
                        for doc, score in self.faiss_store.search(embedding, top_k=top_k):
                            weight = 1.0 if index == 0 else 0.8
                            all_results.append((doc, score * weight))
                else:
                    embedding = await self.embedding_service.embed_text(query)
                    all_results.extend(self.faiss_store.search(embedding, top_k=top_k))
            except Exception as exc:
                logger.warning("Vector retrieval failed; using keyword fallback: %s", exc)
        else:
            logger.info("RAG_ENABLE_EMBEDDING is disabled; using keyword fallback only")

        if not all_results:
            all_results = self._fallback_keyword_search(search_queries, top_k)

        final_results = self._deduplicate_and_rerank(all_results, top_k, min_score)
        self._add_to_cache(cache_key, final_results)

        logger.info("RAG retrieval finished: results=%s", len(final_results))
        return final_results

    async def _ensure_knowledge_loaded(self) -> None:
        """Load knowledge files and build a vector index when needed."""

        if self._knowledge_bootstrapped:
            return
        self._knowledge_bootstrapped = True

        if self.faiss_store.count > 0:
            return

        loader = DocumentLoader(chunk_size=500, chunk_overlap=50)

        for docs_dir in self._candidate_knowledge_dirs():
            if not docs_dir.exists() or not docs_dir.is_dir():
                continue

            documents = loader.load_directory(str(docs_dir), recursive=True)
            if not documents:
                continue

            logger.info("Loaded raw RAG docs: dir=%s chunks=%s", docs_dir, len(documents))

            try:
                texts = [str(doc.get("content", "")) for doc in documents]
                embeddings = await self.embedding_service.embed_texts(texts, batch_size=5)

                valid_vectors: List[List[float]] = []
                valid_docs: List[Dict[str, Any]] = []
                for embedding, doc in zip(embeddings, documents):
                    if embedding and not all(value == 0.0 for value in embedding):
                        valid_vectors.append(embedding)
                        valid_docs.append(doc)

                if valid_vectors:
                    self.faiss_store.add_documents(valid_vectors, valid_docs)
                    self.faiss_store.save()
                    logger.info("Built RAG vector index: dir=%s chunks=%s", docs_dir, len(valid_docs))
                else:
                    self.faiss_store.documents.extend(documents)
                    logger.warning("Embeddings unavailable; switched to keyword-only document fallback")
            except Exception as exc:
                logger.warning("Vector index build failed; using keyword docs fallback: %s", exc)
                self.faiss_store.documents.extend(documents)

            if self.faiss_store.count > 0:
                return

    def _candidate_knowledge_dirs(self) -> List[Path]:
        app_dir = Path(__file__).resolve().parent.parent
        candidates: List[Path] = []

        env_dir = os.getenv("RAG_DOCS_DIR")
        if env_dir:
            candidates.append(Path(env_dir))

        candidates.extend(
            [
                app_dir.parent / "knowledge_base",
                app_dir / "knowledge_base",
                app_dir / "data" / "rag_docs",
                app_dir / "data" / "knowledge",
                app_dir / "data" / "knowledge_base",
                app_dir / "data" / "docs",
            ]
        )

        unique: List[Path] = []
        seen = set()
        for candidate in candidates:
            normalized = str(candidate.resolve()) if candidate.exists() else str(candidate)
            if normalized in seen:
                continue
            seen.add(normalized)
            unique.append(candidate)
        return unique

    def _fallback_keyword_search(
        self,
        queries: List[str],
        top_k: int,
    ) -> List[Tuple[Dict[str, Any], float]]:
        """Fallback to simple keyword matching when vector search is unavailable."""

        terms = self._build_fallback_terms(queries)
        if not terms:
            return []

        candidates: List[Tuple[Dict[str, Any], float]] = []
        for doc in self.faiss_store.documents:
            content = str(doc.get("content", "")).lower()
            if not content:
                continue

            score = 0.0
            for term in terms:
                hits = content.count(term)
                if hits > 0:
                    score += min(hits, 3) * (1.2 if len(term) >= 4 else 1.0)

            if score > 0:
                candidates.append((doc, score))

        if not candidates:
            return []

        candidates.sort(key=lambda item: item[1], reverse=True)
        max_score = candidates[0][1] or 1.0
        keep_count = max(top_k * 3, top_k)
        return [(doc, score / max_score) for doc, score in candidates[:keep_count]]

    def _build_fallback_terms(self, queries: List[str]) -> List[str]:
        terms: List[str] = []
        for query in queries:
            text = (query or "").strip().lower()
            if not text:
                continue

            terms.append(text)

            normalized = re.sub(r"[，。,.!?！？、；;:：()（）\\[\\]{}\"'`]+", " ", text)
            for token in normalized.split():
                token = token.strip()
                if len(token) >= 2:
                    terms.append(token)

            terms.extend(self._extract_cjk_ngrams(normalized))

        unique_terms: List[str] = []
        seen = set()
        for term in terms:
            if term not in seen:
                seen.add(term)
                unique_terms.append(term)

        return unique_terms[:120]

    def _extract_cjk_ngrams(self, text: str) -> List[str]:
        cjk_chars = [ch for ch in text if "\u4e00" <= ch <= "\u9fff"]
        if len(cjk_chars) < 2:
            return []

        joined = "".join(cjk_chars)
        grams: List[str] = []
        for n in (2, 3):
            if len(joined) < n:
                continue
            for index in range(0, len(joined) - n + 1):
                grams.append(joined[index : index + n])
        return grams

    def _deduplicate_and_rerank(
        self,
        results: List[Tuple[Dict[str, Any], float]],
        top_k: int,
        min_score: float,
    ) -> List[Dict[str, Any]]:
        seen_contents: Dict[str, Tuple[Dict[str, Any], float]] = {}
        for doc, score in results:
            content = str(doc.get("content", ""))
            previous = seen_contents.get(content)
            if previous is None or previous[1] < score:
                seen_contents[content] = (doc, score)

        sorted_results = sorted(seen_contents.values(), key=lambda item: item[1], reverse=True)
        filtered = [(doc, score) for doc, score in sorted_results if score >= min_score]

        final: List[Dict[str, Any]] = []
        for doc, score in filtered[:top_k]:
            item = dict(doc)
            item["score"] = score
            final.append(item)
        return final

    def _add_to_cache(self, key: str, value: List[Dict[str, Any]]) -> None:
        if len(self._cache) >= self._cache_max_size:
            oldest_key = next(iter(self._cache))
            del self._cache[oldest_key]
        self._cache[key] = value

    def clear_cache(self) -> None:
        self._cache.clear()
        logger.info("RAG cache cleared")

    async def build_context(self, query: str, max_tokens: int = 2000) -> str:
        """Build a formatted context string from retrieved docs."""

        results = await self.retrieve(
            query,
            top_k=5,
            use_keywords=settings.keyword_extraction_enabled,
        )
        if not results:
            return ""

        context_parts = [
            "相关知识库内容（仅供参考，不作为专业结论或事实依据）：",
            "",
        ]
        total_chars = 0
        max_chars = max_tokens * 2

        for index, doc in enumerate(results, 1):
            content = doc.get("content", "")
            source = doc.get("filename", "未知来源")
            score = doc.get("score", 0)
            entry = f"[{index}] 来源: {source} (相关度: {score:.2f})\n{content}\n"

            if total_chars + len(entry) > max_chars:
                break

            context_parts.append(entry)
            total_chars += len(entry)

        return "\n".join(context_parts)


_rag_retriever: Optional[RAGRetriever] = None


def get_rag_retriever() -> RAGRetriever:
    """Return the process-wide RAG retriever."""

    global _rag_retriever
    if _rag_retriever is None:
        _rag_retriever = RAGRetriever()
    return _rag_retriever
