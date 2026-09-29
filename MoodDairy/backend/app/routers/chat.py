"""Chat router with diary context, agent tools, and streaming support."""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
from pathlib import Path
from datetime import datetime
from typing import Any, Dict, List, Optional
from collections.abc import AsyncIterator

import httpx
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import StreamingResponse
from openai import OpenAI
from pydantic import BaseModel, Field, model_validator
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent import PlanStoreError, execute_tool, get_plan_store, get_tool_definition, get_tool_schemas
from app.agent.client_intents import match_client_action_intent
from app.agent.tools import build_tool_plan_preview
from app.config import settings
from app.database import get_db
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


def _parse_embedded_function_calls(text: str) -> tuple[str, List[Dict[str, str]]]:
    """从正文中剥离 <|FunctionCallBegin|>...<|FunctionCallEnd|>，并解析为工具调用列表。"""
    if not text or _FC_BEGIN not in text:
        return text.strip(), []

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
    message: str = Field(default="", max_length=1000, description="User message")
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

        if self.confirmation is not None and self.message and len(self.message) > 1000:
            raise ValueError("message must be <= 1000 characters")

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

        try:
            if request.confirmation is not None:
                async for event in _handle_confirmation_turn(request, db):
                    yield _encode_sse(event)
            else:
                async for event in _handle_agent_turn(request, db):
                    yield _encode_sse(event)
        except Exception as exc:
            logger.error("Streaming chat request failed: user_id=%s error=%s", request.user_id, exc, exc_info=True)
            yield _encode_sse(
                _done_event(
                    final_state="error",
                    reply="",
                    error_code="STREAM_INTERNAL_ERROR",
                    error_message=f"Chat request failed: {exc}",
                )
            )

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


async def _handle_agent_turn(request: ChatRequest, db: AsyncSession):
    message_text = _normalize_message(request.message, request.merged_image_data_urls)
    full_context = await _build_full_context(request.merged_diary_summaries, message_text)
    valid_images = _valid_images(request.merged_image_data_urls)
    orchestration = EmotionOrchestrationService().evaluate(
        message_text,
        request.merged_diary_summaries,
    )

    logger.info(
        "Agent turn: user_id=%s message_length=%s summaries=%s images=%s",
        request.user_id,
        len(message_text),
        len(request.merged_diary_summaries),
        len(valid_images),
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
        "你是一位温暖、富有同理心的日记助手，擅长倾听和陪伴。\n\n"
        "【核心原则】\n"
        "1. 倾听优先：先理解用户的感受，再给予回应。不要急于给建议或解决方案。\n"
        "2. 多轮对话：心理咨询和情感支持需要通过多轮对话深入了解。不要一轮就给出完整答案或建议列表。\n"
        "3. 引导探索：用开放式问题帮助用户思考和表达，如'能再多说说吗'、'那一刻你是什么感受'、'你希望发生什么'。\n"
        "4. 共情回应：先确认和回应用户的情绪，再讨论具体问题。\n"
        "5. 个性化关怀：结合用户的日记内容给出有针对性的回应。\n\n"
        "【什么时候调用工具】\n"
        "- 页面跳转：用户想打开某个页面时，立即使用对应的客户端动作工具（无需确认）\n"
        "  · '打开白噪音'/'播放白噪音'/'听下雨声' → open_white_noise\n"
        "  · '做测试'/'打开测试中心'/'测评' → open_test_hub\n"
        "  · '画画'/'去画画'/'涂鸦' → open_drawing\n"
        "  · '写日记'/'新建日记'/'编辑日记' → open_diary_editor\n"
        "- 数据操作：用户需要创建/查询日记、待办时使用\n"
        "  · 创建日记 → create_diary（需确认）\n"
        "  · 查询日记 → search_diaries / get_recent_summaries\n"
        "  · 创建待办 → create_todo（需确认）\n\n"
        "【对话风格】\n"
        "- 语气温暖、自然，像朋友聊天一样\n"
        "- 避免过于正式或说教\n"
        "- 回应要有具体性，不要泛泛而谈\n"
        "- 短确认（'好的'、'嗯'、'ok'）直接回应即可\n"
        "- 遇到明确操作请求时，先引导确认需求，再用工具执行\n"
        "- 遇到用户情绪低落时，给予更多耐心和倾听\n\n"
        "【特别注意】\n"
        "- 如果用户只是倾诉，专注于倾听和共情\n"
        "- 如果用户寻求建议，用提问引导他们自己找到答案\n"
        "- 除非用户明确要求，否则不要一口气给出多个建议\n"
        "- 保持对话的自然流畅，不要机械地堆砌规则"
    )

    user_prompt = (
        f"用户消息：{user_message}\n\n"
        f"日记摘要上下文：\n{context}\n\n"
        "根据用户的消息，决定是直接回应还是调用工具。"
        "如果用户情绪低落或倾诉为主，先给予共情和倾听。"
        "如果用户询问具体信息或请求操作，再使用相应工具。"
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
        "stream": True,
        "temperature": 0.4,
        "max_tokens": 500,
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

            async for line in response.aiter_lines():
                line = line.strip()
                with _open_chat_debug_log() as _dbg_f:
                    import json
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

    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            response = await client.get(url)
            response.raise_for_status()
            content_type = response.headers.get("content-type", "image/jpeg")
            b64_data = base64.b64encode(response.content).decode("utf-8")
            return f"data:{content_type};base64,{b64_data}"
    except Exception as exc:
        logger.warning("Failed to fetch image URL %s for base64 conversion: %s", url, exc)
        return url


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
            max_tokens=500,
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
