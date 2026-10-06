"""Chat router with diary context, agent tools, and streaming support."""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import re
from pathlib import Path
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from collections.abc import AsyncIterator

import httpx
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import StreamingResponse
from openai import OpenAI
from pydantic import BaseModel, Field, model_validator
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent import PlanStoreError, execute_tool, get_plan_store, get_tool_definition, get_tool_schemas
from app.agent.client_intents import match_client_action_intent
from app.agent.tools import build_tool_plan_preview
from app.config import settings
from app.database import get_db
from app.models.database import EmotionRecord
from app.models.schemas import ErrorResponse
from app.security.deps import AuthenticatedUserId, ensure_user_match
from app.security.pii import sanitize_for_llm
from app.services.emotion_orchestration_service import EmotionOrchestrationService
from app.utils.ark_runtime import build_async_http_client, build_http_client, get_ark_base_url

router = APIRouter(prefix="/chat", tags=["chat"])
logger = logging.getLogger(__name__)

# backend/logs/chat_debug.log — 相对项目路径，避免硬编码盘符
_CHAT_DEBUG_LOG = Path(__file__).resolve().parent.parent.parent / "logs" / "chat_debug.log"


def _open_chat_debug_log():
    _CHAT_DEBUG_LOG.parent.mkdir(parents=True, exist_ok=True)
    return open(_CHAT_DEBUG_LOG, "a", encoding="utf-8")


# 豆包等模型有时把函数调用写在正文里，而不是 delta.tool_calls
_FC_BEGIN = "<|FunctionCallBegin|>"
_FC_END = "<|FunctionCallEnd|>"


def _parse_tool_text_tags(text: str) -> tuple[str, List[Dict[str, str]]]:
    """兼容部分模型直接写 <tool_name key="value"> 或 <tool_name>{"json"}</tool_name> 形式的调用。"""
    if not text or "<" not in text:
        return text, []

    from app.agent.tools import TOOL_REGISTRY

    names = sorted(TOOL_REGISTRY.keys(), key=len, reverse=True)
    if not names:
        return text, []
    name_alt = "|".join(re.escape(n) for n in names)
    # 开标签 + 属性；可选闭合标签及标签体
    tag_re = re.compile(
        r"<\s*(" + name_alt + r")\b([^>]*)>(?:(.*?)</\s*\1\s*>)?",
        re.DOTALL,
    )
    attr_re = re.compile(
        r"""([A-Za-z_][\w]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
    )

    calls: List[Dict[str, str]] = []

    def _replace(match: "re.Match[str]") -> str:
        name = match.group(1)
        attrs = match.group(2) or ""
        body = (match.group(3) or "").strip()

        params: Dict[str, Any] = {}
        for key, dq, sq in attr_re.findall(attrs):
            params[key] = dq if dq else sq
        if body.startswith("{"):
            try:
                parsed = json.loads(body)
                if isinstance(parsed, dict):
                    params = parsed
            except (json.JSONDecodeError, ValueError):
                pass

        calls.append(
            {
                "id": f"texttag-{len(calls)}",
                "name": name,
                "arguments": json.dumps(params, ensure_ascii=False),
            }
        )
        return ""

    clean = tag_re.sub(_replace, text).strip()
    return clean, calls


def _parse_embedded_function_calls(text: str) -> tuple[str, List[Dict[str, str]]]:
    """从正文中剥离 <|FunctionCallBegin|>...<|FunctionCallEnd|>，并解析为工具调用列表。"""
    if not text:
        return text, []

    calls: List[Dict[str, str]] = []
    out_parts: List[str] = []
    rest = text
    while _FC_BEGIN in rest:
        before, _, after = rest.partition(_FC_BEGIN)
        out_parts.append(before)
        if _FC_END not in after:
            logger.warning("Embedded function call missing %s; stripping tail", _FC_END)
            rest = ""
            break
        inner, _, rest = after.partition(_FC_END)
        inner = inner.strip()
        if inner.startswith(">"):
            inner = inner[1:].strip()
        try:
            arr = json.loads(inner)
            if not isinstance(arr, list):
                arr = [arr]
            for item in arr:
                if not isinstance(item, dict) or not item.get("name"):
                    continue
                name = str(item["name"])
                params = item.get("parameters")
                if params is None:
                    params = {}
                if not isinstance(params, dict):
                    params = {}
                calls.append(
                    {
                        "id": f"embedded-{len(calls)}",
                        "name": name,
                        "arguments": json.dumps(params, ensure_ascii=False),
                    }
                )
        except (json.JSONDecodeError, TypeError, ValueError) as exc:
            logger.warning("Embedded function call JSON parse failed: %s inner=%r", exc, inner[:200])

    out_parts.append(rest)
    clean = "".join(out_parts).strip()

    # 兼容裸写的 <tool_name ...> 文本标签
    if "<" in clean:
        clean, tag_calls = _parse_tool_text_tags(clean)
        calls.extend(tag_calls)

    return clean, calls


def _merge_embedded_into_pending(
    pending: Dict[int, Dict[str, Any]],
    embedded: List[Dict[str, str]],
) -> None:
    if not embedded:
        return
    next_idx = max(pending.keys(), default=-1) + 1
    for c in embedded:
        pending[next_idx] = {
            "id": c.get("id") or f"embedded-{next_idx}",
            "name": c["name"],
            "arguments": c.get("arguments") or "{}",
        }
        next_idx += 1


async def _finalize_stream_content(
    accumulated_text: str,
    pending_tool_calls: Dict[int, Dict[str, Any]],
) -> AsyncIterator[Dict[str, Any]]:
    """流结束后：解析正文内嵌函数调用，再按顺序输出 chunk / tool_calls / done。"""
    clean_text, embedded_calls = _parse_embedded_function_calls(accumulated_text)
    _merge_embedded_into_pending(pending_tool_calls, embedded_calls)

    acc = ""
    for piece in _chunk_text(clean_text):
        acc += piece
        yield {"type": "chunk", "delta": piece, "accumulated": acc}

    if pending_tool_calls:
        parsed_calls = [
            {
                "id": tc["id"],
                "name": tc["name"],
                "arguments": tc["arguments"],
            }
            for _, tc in sorted(pending_tool_calls.items())
            if tc.get("name")
        ]
        if parsed_calls:
            yield {"type": "tool_calls", "calls": parsed_calls}

    yield {"type": "done", "accumulated_text": clean_text}


MAX_OPERATION_CALLS_PER_TURN = 1
MAX_READ_ONLY_CALLS_PER_TURN = 3
MAX_AGENT_ITERATIONS = 6
TOOL_EXECUTION_TIMEOUT_SECONDS = 20


class DiarySummarySimple(BaseModel):
    """Compact diary summary payload used by chat."""

    diary_id: int
    diary_date: str
    summary: str
    keywords: List[str]
    primary_emotion: str
    emotion_score: int


class ChatContext(BaseModel):
    diary_summaries: List[DiarySummarySimple] = Field(default_factory=list)
    image_data_urls: List[str] = Field(default_factory=list)


class ChatConfirmation(BaseModel):
    plan_id: str = Field(..., min_length=1, max_length=128)
    approved: bool
    edited_arguments: Optional[Dict[str, Any]] = None


class ChatRequest(BaseModel):
    """Unified chat request payload for both normal and confirmation turns."""

    user_id: int = Field(..., description="User ID")
    message: str = Field(default="", max_length=2000, description="User message")
    conversation_id: str = Field(..., min_length=1, max_length=128)
    context: ChatContext = Field(default_factory=ChatContext)
    confirmation: Optional[ChatConfirmation] = None

    # Legacy compatibility fields
    diary_summaries: List[DiarySummarySimple] = Field(default_factory=list)
    image_data_urls: List[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_payload(self) -> "ChatRequest":
        message_text = self.message.strip()
        images = self.merged_image_data_urls

        if self.confirmation is None and not message_text and not images:
            raise ValueError("message cannot be blank when confirmation is null")

        if self.confirmation is not None and self.message and len(self.message) > 2000:
            raise ValueError("message must be <= 2000 characters")

        return self

    @property
    def merged_diary_summaries(self) -> List[DiarySummarySimple]:
        return self.context.diary_summaries or self.diary_summaries

    @property
    def merged_image_data_urls(self) -> List[str]:
        return self.context.image_data_urls or self.image_data_urls


class ChatResponse(BaseModel):
    """Chat response payload."""

    reply: str = Field(..., description="AI reply")
    timestamp: str = Field(..., description="Response timestamp")


@router.post("/", response_model=ChatResponse, include_in_schema=False)
@router.post(
    "",
    response_model=ChatResponse,
    summary="Chat with diary context",
    description="Send a message to the assistant with optional diary summaries and images.",
    responses={
        200: {"description": "Reply generated successfully"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal server error"},
    },
)
async def chat(
    request: ChatRequest,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db),
) -> ChatResponse:
    del db
    ensure_user_match(current_user_id, request.user_id)

    if request.confirmation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="/chat does not support confirmation flow; use /chat/stream",
        )

    message_text = _normalize_message(request.message, request.merged_image_data_urls)
    try:
        full_context = await _build_full_context(request.merged_diary_summaries, message_text)
        reply = await _call_llm(
            user_message=message_text,
            context=full_context,
            image_data_urls=request.merged_image_data_urls,
        )
        return ChatResponse(reply=reply, timestamp=datetime.now().isoformat())
    except Exception as exc:
        logger.error("Chat request failed: user_id=%s error=%s", request.user_id, exc)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Chat request failed: {exc}",
        ) from exc


@router.post(
    "/stream",
    summary="Stream chat replies with diary context and tool execution",
    description="Streams assistant output as server-sent events.",
    responses={
        200: {"description": "Streaming response"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal server error"},
    },
)
async def chat_stream(
    request: ChatRequest,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db),
) -> StreamingResponse:
    ensure_user_match(current_user_id, request.user_id)
    logger.info(
        "Received streaming chat request: user_id=%s conversation_id=%s has_confirmation=%s",
        request.user_id,
        request.conversation_id,
        request.confirmation is not None,
    )

    async def event_generator():
        yield _encode_sse({"type": "start", "timestamp": datetime.now().isoformat()})

        # 2026-10-02: 大模型首字延迟可达 40s+，App 读超时 30s 会断连。
        # 在等待期间每 12s 发送 SSE 注释行(": keepalive")保活，App 端解析器会忽略它。
        queue: asyncio.Queue = asyncio.Queue()

        async def run_turn():
            try:
                if request.confirmation is not None:
                    async for event in _handle_confirmation_turn(request, db):
                        await queue.put(("event", event))
                else:
                    async for event in _handle_agent_turn(request, db):
                        await queue.put(("event", event))
            except Exception as exc:
                logger.error("Streaming chat request failed: user_id=%s error=%s", request.user_id, exc, exc_info=True)
                await queue.put(
                    (
                        "event",
                        _done_event(
                            final_state="error",
                            reply="",
                            error_code="STREAM_INTERNAL_ERROR",
                            error_message=f"Chat request failed: {exc}",
                        ),
                    )
                )
            finally:
                await queue.put(("stop", None))

        turn_task = asyncio.create_task(run_turn())
        try:
            while True:
                try:
                    kind, event = await asyncio.wait_for(queue.get(), timeout=12.0)
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
                    continue
                if kind == "stop":
                    break
                yield _encode_sse(event)
        finally:
            turn_task.cancel()

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


async def _handle_confirmation_turn(request: ChatRequest, db: AsyncSession):
    confirmation = request.confirmation
    if confirmation is None:
        yield _done_event(
            final_state="error",
            error_code="CONFIRMATION_MISSING",
            error_message="confirmation is required",
        )
        return

    plan_store = get_plan_store()
    try:
        plan = await plan_store.get_for_confirmation(
            db=db,
            plan_id=confirmation.plan_id,
            user_id=request.user_id,
            conversation_id=request.conversation_id,
        )
    except PlanStoreError as exc:
        yield _done_event(
            final_state="error",
            error_code=exc.code,
            error_message=exc.message,
        )
        return

    if not confirmation.approved:
        await plan_store.mark_cancelled(db, plan.plan_id)
        cancellation_text = "已取消本次操作。"
        yield {
            "type": "tool_status",
            "plan_id": plan.plan_id,
            "tool": plan.tool,
            "status": "cancelled",
            "timestamp": datetime.now().isoformat(),
        }
        for chunk in _chunk_text(cancellation_text):
            yield {"type": "delta", "delta": chunk}
        yield _done_event(final_state="success", reply=cancellation_text)
        return

    effective_arguments = confirmation.edited_arguments or plan.arguments
    yield {
        "type": "tool_status",
        "plan_id": plan.plan_id,
        "tool": plan.tool,
        "status": "running",
        "arguments": effective_arguments,
        "timestamp": datetime.now().isoformat(),
    }

    try:
        result = await asyncio.wait_for(
            execute_tool(
                tool_name=plan.tool,
                arguments=effective_arguments,
                db=db,
                user_id=request.user_id,
                conversation_id=request.conversation_id,
            ),
            timeout=TOOL_EXECUTION_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        await plan_store.mark_failed(
            db,
            plan.plan_id,
            error_code="TOOL_TIMEOUT",
            error_message="tool execution timed out",
        )
        message = "工具执行超时，请稍后重试。"
        yield {
            "type": "tool_result",
            "plan_id": plan.plan_id,
            "tool": plan.tool,
            "ok": False,
            "error": "tool execution timed out",
            "timestamp": datetime.now().isoformat(),
        }
        for chunk in _chunk_text(message):
            yield {"type": "delta", "delta": chunk}
        yield _done_event(
            final_state="error",
            reply=message,
            error_code="TOOL_TIMEOUT",
            error_message="tool execution timed out",
        )
        return
    except Exception as exc:
        await plan_store.mark_failed(
            db,
            plan.plan_id,
            error_code="TOOL_EXECUTION_FAILED",
            error_message=str(exc),
        )
        message = f"操作执行失败：{exc}"
        yield {
            "type": "tool_result",
            "plan_id": plan.plan_id,
            "tool": plan.tool,
            "ok": False,
            "error": str(exc),
            "timestamp": datetime.now().isoformat(),
        }
        for chunk in _chunk_text(message):
            yield {"type": "delta", "delta": chunk}
        yield _done_event(
            final_state="error",
            reply=message,
            error_code="TOOL_EXECUTION_FAILED",
            error_message=str(exc),
        )
        return

    await plan_store.mark_executed(db, plan.plan_id)

    if result.client_action:
        yield {
            "type": "client_action",
            "action": result.client_action.get("action"),
            "payload": result.client_action.get("payload", {}),
            "timestamp": datetime.now().isoformat(),
        }

    yield {
        "type": "tool_result",
        "plan_id": plan.plan_id,
        "tool": result.tool,
        "ok": result.ok,
        "result": result.for_model(),
        "timestamp": datetime.now().isoformat(),
    }

    reply = await _generate_tool_followup_reply(
        original_user_message=plan.request_message,
        context=plan.context_snapshot,
        tool_name=plan.tool,
        tool_payload=result.for_model(),
    )

    for chunk in _chunk_text(reply):
        yield {"type": "delta", "delta": chunk}

    yield _done_event(final_state="success", reply=reply)


async def _fetch_recent_face_signals(
    db: AsyncSession, user_id: int, window_minutes: int = 15
) -> List[Dict[str, Any]]:
    """查询近 window_minutes 分钟内该用户的摄像头表情记录，作为表情信号源。"""
    try:
        window_start = datetime.now() - timedelta(minutes=window_minutes)
        stmt = (
            select(EmotionRecord.emotion_type, EmotionRecord.recorded_at)
            .where(EmotionRecord.user_id == user_id)
            .where(EmotionRecord.source_type == "face_camera")
            .where(EmotionRecord.recorded_at >= window_start)
            .order_by(EmotionRecord.recorded_at.desc())
        )
        result = await db.execute(stmt)
        return [
            {"emotion_type": row.emotion_type, "recorded_at": row.recorded_at}
            for row in result.all()
        ]
    except Exception as exc:
        logger.warning("Fetch recent face signals failed: %s", exc)
        return []


async def _handle_agent_turn(request: ChatRequest, db: AsyncSession):
    message_text = _normalize_message(request.message, request.merged_image_data_urls)
    full_context = await _build_full_context(request.merged_diary_summaries, message_text)
    valid_images = _valid_images(request.merged_image_data_urls)
    face_signals = await _fetch_recent_face_signals(db, request.user_id)
    orchestration = EmotionOrchestrationService().evaluate(
        message_text,
        request.merged_diary_summaries,
        face_signals=face_signals,
    )

    logger.info(
        "Agent turn: user_id=%s message_length=%s summaries=%s images=%s face_signals=%s",
        request.user_id,
        len(message_text),
        len(request.merged_diary_summaries),
        len(valid_images),
        len(face_signals),
    )

    # — Intent shortcut: direct navigation commands are handled without LLM —
    intent = match_client_action_intent(message_text)
    if intent:
        tool_name, arguments = intent
        logger.info("Intent shortcut matched: tool=%s arguments=%s", tool_name, arguments)
        try:
            result = await execute_tool(
                tool_name=tool_name,
                arguments=arguments,
                db=db,
                user_id=request.user_id,
                conversation_id=request.conversation_id,
            )
        except Exception as exc:
            logger.error("Intent shortcut tool execution failed: %s", exc)
            yield _done_event(
                final_state="error",
                reply=f"抱歉，无法打开页面：{exc}",
            )
            return

        if result.client_action:
            yield {
                "type": "client_action",
                "action": result.client_action.get("action"),
                "payload": result.client_action.get("payload", {}),
                "timestamp": datetime.now().isoformat(),
            }

        yield _done_event(final_state="success", reply="**完成**")
        return

    messages = await _build_agent_messages(message_text, full_context, valid_images)
    tools = get_tool_schemas()
    model = os.getenv("ARK_MODEL_NAME", "doubao-seed-1-8-251228")

    # PHASE 1 — ask without tools.
    # This forces a direct text reply, bypassing the empty-response bug
    # where the model sees tools + tool_choice=auto on a short message and
    # returns nothing.
    phase1_text = ""
    phase1_tool_calls: List[Dict[str, str]] = []

    try:
        with _open_chat_debug_log() as _dbg_f5:
            import json as _j
            _dbg_f5.write(_j.dumps({"sessionId":"1d9790","location":"chat.py:_handle_agent_turn:phase1_start","message":"PHASE1_streaming_started","data":{"tools_provided":False}})+"\n")
        async for event in _stream_llm_events(messages, tools=None):
            event_type = event.get("type", "")

            if event_type == "chunk":
                phase1_text += event["delta"]
                yield {"type": "delta", "delta": event["delta"]}
                with _open_chat_debug_log() as _dbg_f6:
                    import json as _j2
                    _dbg_f6.write(_j2.dumps({"sessionId":"1d9790","location":"chat.py:_handle_agent_turn:chunk","message":"chunk_received","data":{"delta":event["delta"],"accumulated_len":len(phase1_text)}})+"\n")

            elif event_type == "tool_calls":
                phase1_tool_calls = event["calls"]
                with _open_chat_debug_log() as _dbg_f7:
                    import json as _j3
                    _dbg_f7.write(_j3.dumps({"sessionId":"1d9790","location":"chat.py:_handle_agent_turn:tool_calls","message":"tool_calls_received","data":{"count":len(event["calls"])}})+"\n")

            elif event_type == "done":
                with _open_chat_debug_log() as _dbg_f8:
                    import json as _j4
                    _dbg_f8.write(_j4.dumps({"sessionId":"1d9790","location":"chat.py:_handle_agent_turn:phase1_done","message":"PHASE1_done","data":{"accumulated_text_len":len(phase1_text),"tool_calls_count":len(phase1_tool_calls)}})+"\n")
                break

    except Exception as exc:
        logger.error(
            "LLM streaming failed (phase 1): model=%s error=%s messages_count=%s",
            model,
            exc,
            len(messages),
            exc_info=True,
        )
        fallback = "我暂时无法生成回复，请稍后再试。"
        for chunk in _chunk_text(fallback):
            yield {"type": "delta", "delta": chunk}
        yield _done_event(
            final_state="error",
            reply=fallback,
            error_code="STREAM_LLM_ERROR",
            error_message=str(exc),
        )
        return

    assistant_text = phase1_text.strip()

    # 仅有普通回复、无工具请求时直接结束（有工具请求时必须进入 Phase 2 执行）
    if assistant_text and not phase1_tool_calls:
        for orchestration_event in _build_orchestration_events(orchestration):
            yield orchestration_event
        yield _done_event(final_state="success", reply=assistant_text)
        return

    # Neither text nor tools — provide a graceful default.
    if not phase1_tool_calls:
        with _open_chat_debug_log() as _dbg_f3:
            import json as _j
            _dbg_f3.write(_j.dumps({"sessionId":"1d9790","location":"chat.py:_handle_agent_turn:phase1_empty","message":"PHASE1_EMPTY_triggered","data":{"assistant_text_len":len(assistant_text),"tool_calls_count":len(phase1_tool_calls)}})+"\n")
        logger.warning(
            "Phase 1 empty (no text, no tools); user_id=%s message=%r",
            request.user_id,
            message_text,
        )
        for orchestration_event in _build_orchestration_events(orchestration):
            yield orchestration_event
        yield _done_event(
            final_state="success",
            reply="好的，我收到了。有什么需要帮忙的吗？",
        )
        return

    # PHASE 2 — tool execution loop（首轮可复用 Phase 1 解析出的 tool_calls，避免再调一轮 LLM）
    operation_calls = 0
    read_only_calls = 0

    for iteration in range(MAX_AGENT_ITERATIONS):
        pending_tool_calls: List[Dict[str, str]] = []
        accumulated_text = ""

        if iteration == 0 and phase1_tool_calls:
            pending_tool_calls = list(phase1_tool_calls)
            accumulated_text = assistant_text
        else:
            try:
                async for event in _stream_llm_events(messages, tools):
                    event_type = event.get("type", "")

                    if event_type == "chunk":
                        accumulated_text += event["delta"]
                        yield {"type": "delta", "delta": event["delta"]}

                    elif event_type == "tool_calls":
                        pending_tool_calls = event["calls"]

                    elif event_type == "done":
                        break

            except Exception as exc:
                logger.error(
                    "LLM streaming failed (phase 2): model=%s error=%s messages_count=%s",
                    model,
                    exc,
                    len(messages),
                    exc_info=True,
                )
                fallback = "我暂时无法生成回复，请稍后再试。"
                for chunk in _chunk_text(fallback):
                    yield {"type": "delta", "delta": chunk}
                yield _done_event(
                    final_state="error",
                    reply=fallback,
                    error_code="STREAM_LLM_ERROR",
                    error_message=str(exc),
                )
                return

        assistant_text = accumulated_text.strip()
        tool_calls = pending_tool_calls

        if tool_calls:
            messages.append({"role": "assistant", "content": assistant_text or ""})
            if assistant_text:
                messages[-1]["content"] = assistant_text

        if not tool_calls:
            final_reply = assistant_text or "好的，我收到了。有什么需要帮忙的吗？"
            if not assistant_text:
                for chunk in _chunk_text(final_reply):
                    yield {"type": "delta", "delta": chunk}
            for orchestration_event in _build_orchestration_events(orchestration):
                yield orchestration_event
            yield _done_event(final_state="success", reply=final_reply)
            return

        client_only_batch = True
        last_tool_payload: Dict[str, Any] = {}

        for call in tool_calls:
            tool_name = call["name"]
            tool_call_id = call["id"]
            raw_arguments = call["arguments"]

            definition = get_tool_definition(tool_name)
            if definition is None:
                message = f"我找不到可用工具 {tool_name}，请换一种说法。"
                for chunk in _chunk_text(message):
                    yield {"type": "delta", "delta": chunk}
                yield _done_event(
                    final_state="error",
                    reply=message,
                    error_code="TOOL_NOT_FOUND",
                    error_message=f"unknown tool: {tool_name}",
                )
                return

            if definition.mode != "client_action":
                client_only_batch = False

            try:
                arguments = _parse_tool_arguments(raw_arguments)
            except ValueError as exc:
                clarification = f"工具参数解析失败（{tool_name}）：{exc}。请补充更明确的信息。"
                for chunk in _chunk_text(clarification):
                    yield {"type": "delta", "delta": chunk}
                yield _done_event(
                    final_state="error",
                    reply=clarification,
                    error_code="TOOL_ARGUMENTS_INVALID",
                    error_message=str(exc),
                )
                return

            if definition.mode == "operation":
                if operation_calls >= MAX_OPERATION_CALLS_PER_TURN:
                    message = "本轮最多执行 1 次操作类工具，请确认后再继续下一步。"
                    for chunk in _chunk_text(message):
                        yield {"type": "delta", "delta": chunk}
                    yield _done_event(
                        final_state="error",
                        reply=message,
                        error_code="OPERATION_LIMIT_REACHED",
                        error_message="single-turn operation limit reached",
                    )
                    return

                operation_calls += 1
                plan = await get_plan_store().create_plan(
                    db=db,
                    user_id=request.user_id,
                    conversation_id=request.conversation_id,
                    tool=tool_name,
                    mode=definition.mode,
                    requires_confirmation=definition.requires_confirmation,
                    arguments=arguments,
                    request_message=message_text,
                    context_snapshot=full_context,
                )

                yield {
                    "type": "tool_plan",
                    "plan_id": plan.plan_id,
                    "tool": tool_name,
                    "mode": definition.mode,
                    "arguments": arguments,
                    "requires_confirmation": definition.requires_confirmation,
                    "title": definition.display_name,
                    "preview": build_tool_plan_preview(tool_name, arguments),
                    "expires_at": plan.expires_at.isoformat(),
                    "timestamp": datetime.now().isoformat(),
                }
                yield {
                    "type": "awaiting_confirmation",
                    "plan_id": plan.plan_id,
                    "tool": tool_name,
                    "expires_at": plan.expires_at.isoformat(),
                    "timestamp": datetime.now().isoformat(),
                }
                return

            if definition.mode != "client_action":
                if read_only_calls >= MAX_READ_ONLY_CALLS_PER_TURN:
                    message = "本轮只读工具调用已达上限，请精简需求后重试。"
                    for chunk in _chunk_text(message):
                        yield {"type": "delta", "delta": chunk}
                    yield _done_event(
                        final_state="error",
                        reply=message,
                        error_code="READ_ONLY_LIMIT_REACHED",
                        error_message="single-turn read-only limit reached",
                    )
                    return

                read_only_calls += 1
                yield {
                    "type": "tool_status",
                    "tool": tool_name,
                    "tool_call_id": tool_call_id,
                    "status": "running",
                    "arguments": arguments,
                    "timestamp": datetime.now().isoformat(),
                }

            try:
                result = await asyncio.wait_for(
                    execute_tool(
                        tool_name=tool_name,
                        arguments=arguments,
                        db=db,
                        user_id=request.user_id,
                        conversation_id=request.conversation_id,
                    ),
                    timeout=TOOL_EXECUTION_TIMEOUT_SECONDS,
                )
            except asyncio.TimeoutError:
                message = f"工具 {tool_name} 执行超时，请稍后再试。"
                yield {
                    "type": "tool_result",
                    "tool": tool_name,
                    "tool_call_id": tool_call_id,
                    "ok": False,
                    "error": "tool execution timed out",
                    "timestamp": datetime.now().isoformat(),
                }
                for chunk in _chunk_text(message):
                    yield {"type": "delta", "delta": chunk}
                yield _done_event(
                    final_state="error",
                    reply=message,
                    error_code="TOOL_TIMEOUT",
                    error_message="tool execution timed out",
                )
                return
            except Exception as exc:
                message = f"工具 {tool_name} 执行失败：{exc}"
                yield {
                    "type": "tool_result",
                    "tool": tool_name,
                    "tool_call_id": tool_call_id,
                    "ok": False,
                    "error": str(exc),
                    "timestamp": datetime.now().isoformat(),
                }
                for chunk in _chunk_text(message):
                    yield {"type": "delta", "delta": chunk}
                yield _done_event(
                    final_state="error",
                    reply=message,
                    error_code="TOOL_EXECUTION_FAILED",
                    error_message=str(exc),
                )
                return

            if result.client_action:
                yield {
                    "type": "client_action",
                    "action": result.client_action.get("action"),
                    "payload": result.client_action.get("payload", {}),
                    "timestamp": datetime.now().isoformat(),
                }

            result_payload = result.for_model()
            if definition.mode != "client_action":
                yield {
                    "type": "tool_result",
                    "tool": tool_name,
                    "tool_call_id": tool_call_id,
                    "ok": result.ok,
                    "result": result_payload,
                    "timestamp": datetime.now().isoformat(),
                }

            # 社交搜索工具：成功发结果卡片，失败（余额不足/未配置）发锁定充值卡片
            # 歌曲搜索工具：网易云免费接口，直接发歌曲卡片，永不锁定
            if tool_name in ("search_social_content", "search_music"):
                yield {
                    "type": "search_results",
                    "tool_call_id": tool_call_id,
                    "keyword": result.data.get("keyword"),
                    "platform": result.data.get("platform"),
                    "items": result.data.get("items", []),
                    "locked": (not result.ok)
                    and result.data.get("error_code") in ("NO_KEY", "NO_BALANCE"),
                    "error_code": result.data.get("error_code"),
                    "timestamp": datetime.now().isoformat(),
                }

            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": tool_call_id,
                    "name": tool_name,
                    "content": json.dumps(result_payload, ensure_ascii=False),
                }
            )

            last_tool_payload = result_payload

        if client_only_batch:
            reply = "完成任务，还有什么需要帮忙的吗？"
        else:
            reply = await _generate_tool_followup_reply(
                original_user_message=message_text,
                context=full_context,
                tool_name=tool_calls[0]["name"],
                tool_payload=last_tool_payload,
            )

        yield _done_event(final_state="success", reply=reply)
        return

    fallback = "本轮工具调用达到上限，我先给出当前可确认的信息。"
    for chunk in _chunk_text(fallback):
        yield {"type": "delta", "delta": chunk}
    yield _done_event(
        final_state="error",
        reply=fallback,
        error_code="TOOL_LOOP_LIMIT",
        error_message="agent iteration limit reached",
    )


async def _build_full_context(summaries: List[DiarySummarySimple], message_text: str) -> str:
    diary_context = _build_context(summaries)
    rag_query = message_text or "image analysis"
    rag_context = await _retrieve_knowledge(rag_query)
    return f"{diary_context}\n\n{rag_context}" if rag_context else diary_context


async def _retrieve_knowledge(query: str) -> str:
    if not settings.rag_enabled:
        logger.info("RAG is disabled; skipping retrieval")
        return ""

    try:
        from app.rag import get_rag_retriever

        retriever = get_rag_retriever()
        rag_context = await retriever.build_context(query, max_tokens=1500)
        if rag_context:
            logger.info("RAG context retrieved: length=%s", len(rag_context))
        else:
            logger.info("RAG returned no context")
        return rag_context
    except Exception as exc:
        logger.warning("RAG retrieval failed and will be skipped: %s", exc)
        return ""


def _build_context(summaries: List[DiarySummarySimple]) -> str:
    if not summaries:
        return "The user has no recent diary summaries."

    context_parts = ["Recent diary summaries:", ""]
    for summary in summaries:
        context_parts.append(f"- {summary.diary_date}: {summary.summary}")
        if summary.keywords:
            context_parts.append(f"  Keywords: {', '.join(summary.keywords)}")
        context_parts.append(f"  Emotion: {summary.primary_emotion} ({summary.emotion_score}/100)")
        context_parts.append("")
    return "\n".join(context_parts)


def _normalize_message(message: str, image_data_urls: List[str]) -> str:
    message_text = message.strip()
    if not message_text and image_data_urls:
        return "Please help me look at this image."
    return message_text


async def _build_agent_messages(user_message: str, context: str, valid_images: List[str]) -> List[Dict[str, Any]]:
    system_prompt = (
        "你是小嘉然，一位温暖、富有同理心又真正有用的日记助手，擅长倾听、陪伴，也擅长解决实际问题。\n\n"
        "【核心原则】\n"
        "1. 先判断用户要什么：\n"
        "   - 情绪倾诉（难过、焦虑、压力大等）→ 先共情倾听，再温和引导，不急着讲道理。\n"
        "   - 实际问题（牙疼怎么办、失眠怎么缓解、怎么安排时间等）→ 直接给出具体、可操作、完整的答案，\n"
        "     可以分点列出步骤、注意事项和什么情况该就医/求助，像一个既专业又贴心的朋友。\n"
        "   - 两者混合时：先一句话接住情绪，然后马上给实用帮助。\n"
        "2. 回答要充实：用户问了具体问题，就要给出足够详细、真正能用的内容，不要只回一两句空话，\n"
        "   也不要只回反问句。反问只能作为补充，不能代替答案。\n"
        "3. 个性化关怀：结合用户的日记内容给出有针对性的回应。\n\n"
        "【什么时候调用工具】\n"
        "- 页面跳转：用户想打开某个页面时，立即使用对应的客户端动作工具（无需确认）\n"
        "  · '打开白噪音'/'播放白噪音'/'听下雨声' → open_white_noise\n"
        "  · '做测试'/'打开测试中心'/'测评' → open_test_hub\n"
        "  · '画画'/'去画画'/'涂鸦' → open_drawing\n"
        "  · '写日记'/'新建日记'/'编辑日记' → open_diary_editor\n"
        "- 数据操作：用户需要创建/查询日记、待办时使用\n"
        "  · 创建日记 → create_diary（需确认）\n"
        "  · 查询日记 → search_diaries / get_recent_summaries\n"
        "  · 创建待办 → create_todo（需确认）\n"
        "- 内容搜索：用户想搜小红书/抖音/B站/知乎/快手/微博上的内容、教程、灵感时使用 search_social_content\n"
        "  · 例如：'搜一下小红书的冥想教程'、'找找抖音上治愈系视频'、'B站有没有助眠白噪音'、'知乎上大家怎么缓解焦虑'、'看看快手上的搞笑视频'、'微博上最近在聊什么'\n"
        "  · 用户指定平台时填对应 platform（xhs/douyin/bilibili/zhihu/kuaishou/weibo），未指定则默认 auto（小红书+抖音+B站+知乎综合搜索）\n"
        "  · 调用后用一句话总结（如'我帮你找到了 N 条相关内容'），不要罗列所有标题，搜索结果会以卡片形式展示\n"
        "  · 只要用户表达找视频/笔记/问答/教程/攻略/灵感的意图（如'搜一下'、'找找'、'小红书有没有'、'抖音上'、'B站上'、'知乎怎么说'、'推荐几个链接'），必须实际调用 search_social_content，不要仅凭自己的知识回答\n"
        "  · 若工具返回 ok=false 且 error_code=NO_KEY，用温柔的一句话告诉用户：这个功能需要绑定自己的 TikHub 密钥（免费注册，注册即送少量体验额度），点击下方卡片按指引操作即可，不要假装自己已经搜过\n"
        "  · 若工具返回 ok=false 且 error_code=NO_BALANCE，用温柔的一句话告诉用户：TikHub 账户余额不足，点击下方卡片跳转到官方充值页充值后即可继续搜索（约 $0.01/次），不要假装自己已经搜过\n"
        "  · 若工具返回其他错误，简短说明暂时没搜到并建议稍后再试\n"
        "- 歌曲推荐：用户想听歌、让你推荐歌曲/歌手/曲风、想用音乐调节心情时使用 search_music（网易云音乐，免费）\n"
        "  · 例如：'推荐几首周杰伦的歌'、'我想听点助眠的纯音乐'、'有没有适合难过时听的歌'、'来点轻快的歌'\n"
        "  · 只要用户表达想听歌/让你推歌的意图，必须实际调用 search_music，不要仅凭自己的知识列歌名\n"
        "  · 调用后用一句温暖的话总结（如'我给你挑了几首歌，点开卡片就能去网易云听啦'），不要罗列所有歌名，歌曲会以卡片形式展示\n\n"
        "【对话风格】\n"
        "- 语气温暖、自然，像朋友聊天一样，可以适度使用emoji\n"
        "- 避免过于正式或说教\n"
        "- 回应要有具体性，不要泛泛而谈\n"
        "- 短确认（'好的'、'嗯'、'ok'）直接回应即可\n"
        "- 遇到明确操作请求时，先引导确认需求，再用工具执行\n"
        "- 遇到用户情绪低落时，给予更多耐心和倾听\n\n"
        "【特别注意】\n"
        "- 用户倾诉情绪时，专注倾听和共情，不硬塞建议\n"
        "- 用户寻求实际帮助时，直接给出完整、具体、可操作的方案，不要藏着掖着等他追问\n"
        "- 保持对话的自然流畅，不要机械地堆砌规则"
    )

    user_prompt = (
        f"用户消息：{user_message}\n\n"
        f"日记摘要上下文：\n{context}\n\n"
        "根据用户的消息，决定是直接回应还是调用工具。"
        "如果用户情绪低落或倾诉为主，先给予共情和倾听。"
        "如果用户询问实际问题或具体信息，直接给出充实、可操作的完整答案；需要操作时再使用相应工具。"
    )

    base64_images = await _resolve_images_to_base64(valid_images)
    user_content = _build_user_content(user_prompt, base64_images, include_images=True)
    with _open_chat_debug_log() as _dbg_f4:
        import json as _j
        _dbg_f4.write(_j.dumps({"sessionId":"1d9790","location":"chat.py:_build_agent_messages:built","message":"built_agent_messages","data":{"msg_count":2,"system_len":len(system_prompt),"user_prompt_len":len(user_prompt),"images_count":len(base64_images)}})+"\n")
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_content},
    ]


async def _stream_llm_events(
    messages: List[Dict[str, Any]],
    tools: Optional[List[Dict[str, Any]]] = None,
) -> AsyncIterator[Dict[str, Any]]:
    """
    Stream LLM responses from Ark API using SSE (Server-Sent Events).

    Yields events:
        - "chunk": text delta from the model
        - "tool_calls": parsed tool call(s) when function calls are detected
        - "done": when the stream completes

    On error, yields an "error" event and raises.
    """
    api_key = os.getenv("ARK_API_KEY")
    if not api_key:
        raise RuntimeError("ARK_API_KEY is not configured")

    base_url = get_ark_base_url()
    model = os.getenv("ARK_MODEL_NAME", "doubao-seed-1-8-251228")

    sanitized_messages: List[Dict[str, Any]] = []
    for msg in messages:
        item = dict(msg)
        content = item.get("content")
        if isinstance(content, str):
            item["content"] = sanitize_for_llm(content)
        sanitized_messages.append(item)

    payload: Dict[str, Any] = {
        "model": model,
        "messages": sanitized_messages,
        # 2026-10-02: Ark 流式接口在本环境长时间无响应，改为非流式一次性获取
        "stream": False,
        "temperature": 0.4,
        "max_tokens": 1500,
    }
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    log_payload = {**payload, "messages": "[...]"}  # truncate messages for log brevity
    logger.info("Ark API request: base_url=%s model=%s payload=%s", base_url, model, log_payload)

    http_client = build_async_http_client(timeout=90.0)
    try:
        async with http_client.stream(
            "POST",
            f"{base_url}/chat/completions",
            json=payload,
            headers=headers,
        ) as response:
            if response.status_code != 200:
                body = await response.aread()
                body_text = body.decode(errors="replace")
                logger.error(
                    "Ark API non-200 response: status=%s body=%s request_payload=%s",
                    response.status_code,
                    body_text,
                    log_payload,
                )
                raise RuntimeError(f"Ark API error {response.status_code}: {body_text}")

            accumulated_text = ""
            pending_tool_calls: Dict[int, Dict[str, Any]] = {}

            # 2026-10-02: 非流式分支——一次性读取完整 JSON 后直接结算
            if not payload.get("stream"):
                raw = await response.aread()
                data = json.loads(raw.decode(errors="replace"))
                choices = data.get("choices") or []
                if choices:
                    msg = choices[0].get("message") or {}
                    accumulated_text = msg.get("content") or ""
                    for tc in msg.get("tool_calls") or []:
                        idx = len(pending_tool_calls)
                        fn = tc.get("function") or {}
                        pending_tool_calls[idx] = {
                            "id": tc.get("id", ""),
                            "name": fn.get("name", ""),
                            "arguments": fn.get("arguments", ""),
                        }
                async for ev in _finalize_stream_content(accumulated_text, pending_tool_calls):
                    yield ev
                return

            async for line in response.aiter_lines():
                line = line.strip()
                with _open_chat_debug_log() as _dbg_f:
                    _dbg_f.write(json.dumps({"sessionId":"1d9790","location":"chat.py:_stream_llm_events:raw_line","message":"raw_sse_line","data":{"line_len":len(line),"line_preview":line[:200] if line else ""}})+"\n")
                if not line or not line.startswith("data: "):
                    continue

                data_str = line[len("data: ") :]
                if data_str == "[DONE]":
                    async for ev in _finalize_stream_content(accumulated_text, pending_tool_calls):
                        yield ev
                    return

                try:
                    chunk = json.loads(data_str)
                except json.JSONDecodeError:
                    continue

                delta_type = chunk.get("object", "")
                with _open_chat_debug_log() as _dbg_f2:
                    import json as _json
                    _dbg_f2.write(_json.dumps({"sessionId":"1d9790","location":"chat.py:_stream_llm_events:chunk","message":"parsed_chunk","data":{"delta_type":delta_type,"chunk":chunk}})+"\n")
                if delta_type not in ("chat.completion.chunk", "chat.chat.completion.chunk"):
                    continue

                choice = chunk.get("choices", [])
                if not choice:
                    continue
                delta = choice[0].get("delta", {})

                content = delta.get("content")
                if content:
                    accumulated_text += content

                delta_tool_calls = delta.get("tool_calls") or []
                for tc_index, tc_delta in enumerate(delta_tool_calls):
                    idx = tc_delta.get("index", tc_index)
                    if idx not in pending_tool_calls:
                        pending_tool_calls[idx] = {"id": "", "name": "", "arguments": ""}

                    tc_obj = tc_delta.get("function", {})
                    if tc_obj.get("name"):
                        pending_tool_calls[idx]["name"] += tc_obj["name"]
                    if tc_obj.get("arguments"):
                        pending_tool_calls[idx]["arguments"] += tc_obj["arguments"]
                    if tc_delta.get("id"):
                        pending_tool_calls[idx]["id"] = tc_delta["id"]

            async for ev in _finalize_stream_content(accumulated_text, pending_tool_calls):
                yield ev
    finally:
        await http_client.aclose()


def _parse_tool_arguments(raw_arguments: str) -> Dict[str, Any]:
    if raw_arguments is None or raw_arguments == "":
        return {}

    try:
        parsed = json.loads(raw_arguments)
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON arguments: {exc}") from exc

    if not isinstance(parsed, dict):
        raise ValueError("tool arguments must be a JSON object")

    return parsed


async def _generate_tool_followup_reply(
    original_user_message: str,
    context: str,
    tool_name: str,
    tool_payload: Dict[str, Any],
) -> str:
    prompt = (
        f"User request: {original_user_message or '(confirmation turn)'}\n"
        f"Executed tool: {tool_name}\n"
        f"Tool result: {json.dumps(tool_payload, ensure_ascii=False)}\n\n"
        "Please provide a concise, warm explanation to the user in Chinese, "
        "including what was done and what they can do next."
    )

    return await _call_llm(user_message=prompt, context=context, image_data_urls=[])


def _build_orchestration_events(orchestration: Any) -> List[Dict[str, Any]]:
    if orchestration is None or not getattr(orchestration, "should_emit", False):
        return []

    events = [orchestration.signal.to_event()]
    if orchestration.bundle is not None:
        events.append(orchestration.bundle.to_event())
    return events


def _done_event(
    *,
    final_state: str,
    reply: str = "",
    error_code: Optional[str] = None,
    error_message: Optional[str] = None,
) -> Dict[str, Any]:
    event: Dict[str, Any] = {
        "type": "done",
        "final_state": final_state,
        "reply": reply,
        "timestamp": datetime.now().isoformat(),
    }
    if error_code:
        event["error_code"] = error_code
    if error_message:
        event["error_message"] = error_message
    return event


def _chunk_text(text: str, chunk_size: int = 60) -> List[str]:
    normalized = text.strip()
    if not normalized:
        return []
    return [normalized[index:index + chunk_size] for index in range(0, len(normalized), chunk_size)]


def _build_prompts(user_message: str, context: str) -> tuple[str, str]:
    system_prompt = (
        "You are a warm, perceptive diary assistant. "
        "Reply in a natural, supportive, specific way, without sounding preachy."
    )
    user_prompt = (
        f"User message: {user_message}\n\n"
        f"{context}\n\n"
        "Please provide a warm, personalized, concrete reply based on this context."
    )
    return system_prompt, user_prompt


def _build_fallback_prompt(user_prompt: str, image_count: int) -> str:
    return (
        f"{user_prompt}\n\n"
        f"Additional note: the user also uploaded {image_count} image(s). "
        "If you cannot inspect them directly, ask the user for the key details in the images."
    )


def _create_client() -> OpenAI:
    api_key = os.getenv("ARK_API_KEY")
    if not api_key:
        raise RuntimeError("ARK_API_KEY is not configured")

    return OpenAI(
        base_url=get_ark_base_url(),
        api_key=api_key,
        http_client=build_http_client(timeout=90.0),
    )


def _is_data_uri(url: str) -> bool:
    return url.strip().startswith("data:")


async def _fetch_url_as_base64(url: str) -> Optional[str]:
    """Download an image URL and return a base64 data URI.

    Falls back to returning the original URL string if the fetch fails.
    Doubao-seed-2-0-mini (and other Ark multimodal models) accept either
    public HTTPS URLs or data URIs in the ``image_url.url`` field.
    """

    url = url.strip()
    if _is_data_uri(url):
        return url

    # 模拟器发来的 10.0.2.2 是"宿主机"的别名，只有模拟器内部能解析；
    # 后端要下载需换成本机回环地址，否则云端模型拿到这个内网 URL 也下载不了。
    fetch_url = url
    lowered = url.lower()
    if "://10.0.2.2" in lowered:
        fetch_url = url.replace("://10.0.2.2", "://127.0.0.1", 1)

    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            response = await client.get(fetch_url)
            response.raise_for_status()
            content_type = response.headers.get("content-type", "image/jpeg")
            b64_data = base64.b64encode(response.content).decode("utf-8")
            return f"data:{content_type};base64,{b64_data}"
    except Exception as exc:
        logger.warning("Failed to fetch image URL %s for base64 conversion: %s", url, exc)
        # 下载失败的图片直接丢弃，避免把不可达的内网 URL 透传给云端模型导致整轮 400
        return None


async def _resolve_images_to_base64(image_data_urls: List[str]) -> List[str]:
    """Resolve a list of image URLs/data-URIs to base64 data URIs.

    Data URIs are returned as-is. HTTP(S) URLs are downloaded and converted.
    Returns the original strings on failure so the LLM still gets a usable URL.
    """

    if not image_data_urls:
        return []

    results = await asyncio.gather(
        *[_fetch_url_as_base64(url) for url in image_data_urls],
        return_exceptions=True,
    )
    resolved: List[str] = []
    for item in results:
        if isinstance(item, Exception):
            logger.warning("Image resolution failed: %s", item)
            continue
        if item:
            resolved.append(item)
    return resolved


def _build_user_content(prompt: str, valid_images: List[str], include_images: bool) -> object:
    if include_images and valid_images:
        content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        for image_url in valid_images:
            content.append({"type": "image_url", "image_url": {"url": image_url}})
        return content
    return prompt


def _valid_images(image_data_urls: Optional[List[str]]) -> List[str]:
    return [
        item.strip()
        for item in (image_data_urls or [])
        if isinstance(item, str) and item.strip()
    ][:3]


def _extract_text_piece(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and item.get("type") == "text":
                parts.append(str(item.get("text", "")))
            else:
                text = getattr(item, "text", None)
                if text:
                    parts.append(str(text))
        return "".join(parts)
    if content is None:
        return ""
    return str(content)


async def _call_llm(
    user_message: str,
    context: str,
    image_data_urls: Optional[List[str]] = None,
) -> str:
    user_message = sanitize_for_llm(user_message)
    context = sanitize_for_llm(context)
    system_prompt, user_prompt = _build_prompts(user_message, context)
    valid_images = _valid_images(image_data_urls)
    client = _create_client()

    base64_images = await _resolve_images_to_base64(valid_images)

    def sync_call(include_images: bool, prompt_override: Optional[str] = None):
        prompt = prompt_override or user_prompt
        content = _build_user_content(prompt, base64_images if include_images else [], include_images)
        return client.chat.completions.create(
            model=os.getenv("ARK_MODEL_NAME", "doubao-seed-1-8-251228"),
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": content},
            ],
            temperature=0.7,
            max_tokens=1500,
        )

    try:
        loop = asyncio.get_running_loop()
        try:
            response = await loop.run_in_executor(None, lambda: sync_call(True))
        except Exception as exc:
            if not base64_images:
                raise
            logger.warning("Image chat failed, falling back to text-only prompt: %s", exc)
            fallback_prompt = _build_fallback_prompt(user_prompt, len(base64_images))
            response = await loop.run_in_executor(None, lambda: sync_call(False, fallback_prompt))

        if not response.choices:
            logger.warning("LLM returned no choices")
            return "抱歉，我暂时无法生成回复。"

        reply = _extract_text_piece(response.choices[0].message.content).strip()
        if not reply:
            logger.warning("LLM returned an empty reply")
            return "抱歉，我暂时无法生成回复。"
        return reply
    except Exception as exc:
        logger.error("LLM call failed: %s", exc, exc_info=True)
        return "抱歉，AI 服务暂时不可用。"


def _encode_sse(event: Dict[str, Any]) -> str:
    return f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
