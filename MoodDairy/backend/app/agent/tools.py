"""Tool registry and execution layer for chat agent."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date as date_type
from typing import Any, Awaitable, Callable, Dict, List, Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.schemas import CreateDiaryRequest, UpdateDiaryRequest
from app.models.todo_schemas import CreateTodoRequest
from app.services.diary_service import DiaryService
from app.services.extraction_service import ExtractionService
from app.services.todo_service import TodoService


ToolExecutor = Callable[[Dict[str, Any], AsyncSession, int, str], Awaitable["ToolExecutionResult"]]


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    mode: str  # read_only | operation | client_action
    requires_confirmation: bool
    display_name: str
    parameters: Dict[str, Any]
    executor: ToolExecutor

    def openai_schema(self) -> Dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


@dataclass
class ToolExecutionResult:
    ok: bool
    tool: str
    mode: str
    summary: str
    data: Dict[str, Any]
    error: Optional[str] = None
    client_action: Optional[Dict[str, Any]] = None

    def for_model(self) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "ok": self.ok,
            "tool": self.tool,
            "mode": self.mode,
            "summary": self.summary,
            "data": self.data,
        }
        if self.error:
            payload["error"] = self.error
        if self.client_action:
            payload["client_action"] = self.client_action
        return payload


def _parse_date(value: Optional[str], default: date_type) -> date_type:
    if not value:
        return default
    try:
        return date_type.fromisoformat(str(value))
    except ValueError as exc:
        raise ValueError(f"invalid date format: {value}") from exc


def _normalize_tags(raw: Any) -> List[str]:
    if not raw:
        return []
    tags: List[str] = []
    if isinstance(raw, list):
        values = raw
    else:
        values = [raw]
    for item in values:
        if item is None:
            continue
        text = str(item).strip()
        if text:
            tags.append(text[:20])
    return tags[:10]


async def _exec_create_diary(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del conversation_id

    content = str(arguments.get("content", "")).strip()
    if not content:
        raise ValueError("content is required")

    tags = _normalize_tags(arguments.get("tags"))
    diary_date = _parse_date(arguments.get("diary_date"), date_type.today())
    title = str(arguments.get("title", "")).strip() or None
    mood_score = arguments.get("mood_score")

    final_content = content
    if tags:
        final_content = f"{content}\n\n#标签: {'、'.join(tags)}"

    request = CreateDiaryRequest(
        user_id=user_id,
        title=title,
        content=final_content,
        diary_date=diary_date,
        mood_score=mood_score,
    )

    service = DiaryService(db)
    diary = await service.create_diary(request)

    summary = f"已创建 {diary.diary_date.isoformat()} 的日记"
    return ToolExecutionResult(
        ok=True,
        tool="create_diary",
        mode="operation",
        summary=summary,
        data={
            "diary_id": diary.id,
            "diary_date": diary.diary_date.isoformat(),
            "title": diary.title,
            "tags": tags,
        },
    )


async def _exec_update_diary(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del conversation_id

    diary_id_raw = arguments.get("diary_id")
    if diary_id_raw is None:
        raise ValueError("diary_id is required")

    try:
        diary_id = int(diary_id_raw)
    except (TypeError, ValueError) as exc:
        raise ValueError("diary_id must be an integer") from exc

    service = DiaryService(db)
    existing = await service.get_diary_by_id(diary_id, load_media=False)
    if not existing:
        raise ValueError(f"diary {diary_id} not found")
    if int(existing.user_id) != int(user_id):
        raise ValueError("diary does not belong to current user")

    update_payload: Dict[str, Any] = {}
    for field in ("title", "content", "weather", "location", "mood_score", "mood_type", "is_private"):
        if field in arguments and arguments[field] is not None:
            update_payload[field] = arguments[field]

    tags = _normalize_tags(arguments.get("tags"))
    if tags and isinstance(update_payload.get("content"), str):
        update_payload["content"] = f"{update_payload['content'].strip()}\n\n#标签: {'、'.join(tags)}"

    if not update_payload:
        raise ValueError("no updatable fields provided")

    request = UpdateDiaryRequest(**update_payload)
    diary = await service.update_diary(diary_id, request)
    if not diary:
        raise ValueError(f"diary {diary_id} update failed")

    return ToolExecutionResult(
        ok=True,
        tool="update_diary",
        mode="operation",
        summary=f"已更新日记 {diary_id}",
        data={
            "diary_id": diary.id,
            "diary_date": diary.diary_date.isoformat(),
            "title": diary.title,
            "tags": tags,
        },
    )


async def _exec_create_todo(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del conversation_id

    title = str(arguments.get("title", "")).strip()
    if not title:
        raise ValueError("title is required")

    todo_date = _parse_date(arguments.get("todo_date"), date_type.today())
    note = arguments.get("note")

    request = CreateTodoRequest(
        user_id=user_id,
        todo_date=todo_date,
        title=title,
        note=note,
    )

    service = TodoService(db)
    todo = await service.create_todo(request)

    return ToolExecutionResult(
        ok=True,
        tool="create_todo",
        mode="operation",
        summary=f"已创建待办：{todo.title}",
        data={
            "todo_id": todo.id,
            "todo_date": todo.todo_date.isoformat(),
            "title": todo.title,
            "is_done": todo.is_done,
        },
    )


async def _exec_search_diaries(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del conversation_id

    query = str(arguments.get("query", "")).strip()
    if not query:
        raise ValueError("query is required")

    limit_raw = arguments.get("limit", 5)
    offset_raw = arguments.get("offset", 0)
    try:
        limit = max(1, min(20, int(limit_raw)))
        offset = max(0, int(offset_raw))
    except (TypeError, ValueError) as exc:
        raise ValueError("limit and offset must be integers") from exc

    service = DiaryService(db)
    diaries = await service.search_diaries_by_text(user_id=user_id, query=query, limit=limit, offset=offset)

    items = [
        {
            "diary_id": int(diary.id),
            "diary_date": diary.diary_date.isoformat(),
            "title": diary.title,
            "excerpt": (diary.content or "")[:120],
        }
        for diary in diaries
    ]

    return ToolExecutionResult(
        ok=True,
        tool="search_diaries",
        mode="read_only",
        summary=f"共找到 {len(items)} 条相关日记",
        data={"items": items, "query": query},
    )


async def _exec_get_recent_summaries(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del conversation_id

    limit_raw = arguments.get("limit", 5)
    try:
        limit = max(1, min(20, int(limit_raw)))
    except (TypeError, ValueError) as exc:
        raise ValueError("limit must be an integer") from exc

    service = ExtractionService(db)
    summaries = await service.get_summaries(user_id=user_id, limit=limit)

    return ToolExecutionResult(
        ok=True,
        tool="get_recent_summaries",
        mode="read_only",
        summary=f"已返回最近 {len(summaries)} 条摘要",
        data={"items": summaries},
    )


async def _exec_open_white_noise(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del arguments, db, user_id, conversation_id
    action = {"action": "open_white_noise", "payload": {"source": "chat_agent"}}
    return ToolExecutionResult(
        ok=True,
        tool="open_white_noise",
        mode="client_action",
        summary="已准备打开白噪音页面",
        data=action,
        client_action=action,
    )


async def _exec_open_test_hub(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del arguments, db, user_id, conversation_id
    action = {"action": "open_test_hub", "payload": {"source": "chat_agent"}}
    return ToolExecutionResult(
        ok=True,
        tool="open_test_hub",
        mode="client_action",
        summary="已准备打开测试中心",
        data=action,
        client_action=action,
    )


async def _exec_open_drawing(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del db, user_id, conversation_id

    payload: Dict[str, Any] = {"source": "chat_agent"}
    diary_id = arguments.get("diary_id")
    if diary_id is not None:
        try:
            payload["diary_id"] = int(diary_id)
        except (TypeError, ValueError) as exc:
            raise ValueError("diary_id must be an integer") from exc

    action = {"action": "open_drawing", "payload": payload}
    return ToolExecutionResult(
        ok=True,
        tool="open_drawing",
        mode="client_action",
        summary="已准备打开画画页面",
        data=action,
        client_action=action,
    )


async def _exec_open_diary_editor(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del db, user_id, conversation_id

    payload: Dict[str, Any] = {"source": "chat_agent"}

    diary_id = arguments.get("diary_id")
    if diary_id is not None:
        try:
            payload["diary_id"] = int(diary_id)
        except (TypeError, ValueError) as exc:
            raise ValueError("diary_id must be an integer") from exc

    diary_date = arguments.get("diary_date")
    if diary_date:
        payload["diary_date"] = _parse_date(str(diary_date), date_type.today()).isoformat()

    action = {"action": "open_diary_editor", "payload": payload}
    return ToolExecutionResult(
        ok=True,
        tool="open_diary_editor",
        mode="client_action",
        summary="已准备打开日记编辑页面",
        data=action,
        client_action=action,
    )


async def _exec_open_todo_create(
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    del db, user_id, conversation_id

    payload: Dict[str, Any] = {"source": "chat_agent"}

    todo_date = _parse_date(arguments.get("todo_date"), date_type.today())
    payload["todo_date"] = todo_date.isoformat()

    action = {"action": "open_todo_create", "payload": payload}
    return ToolExecutionResult(
        ok=True,
        tool="open_todo_create",
        mode="client_action",
        summary=f"已准备打开待办创建页面（{todo_date.isoformat()}）",
        data=action,
        client_action=action,
    )


TOOL_REGISTRY: Dict[str, ToolDefinition] = {
    "create_diary": ToolDefinition(
        name="create_diary",
        description="Create a diary entry for the current user.",
        mode="operation",
        requires_confirmation=True,
        display_name="创建日记",
        parameters={
            "type": "object",
            "properties": {
                "content": {"type": "string", "description": "Diary content"},
                "title": {"type": "string", "description": "Optional diary title"},
                "diary_date": {"type": "string", "description": "Date in YYYY-MM-DD"},
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Optional tags",
                },
                "mood_score": {"type": "integer", "minimum": 1, "maximum": 100},
            },
            "required": ["content"],
            "additionalProperties": False,
        },
        executor=_exec_create_diary,
    ),
    "update_diary": ToolDefinition(
        name="update_diary",
        description="Update an existing diary entry that belongs to the current user.",
        mode="operation",
        requires_confirmation=True,
        display_name="编辑日记",
        parameters={
            "type": "object",
            "properties": {
                "diary_id": {"type": "integer"},
                "title": {"type": "string"},
                "content": {"type": "string"},
                "weather": {"type": "string"},
                "location": {"type": "string"},
                "mood_score": {"type": "integer", "minimum": 1, "maximum": 100},
                "mood_type": {"type": "string"},
                "is_private": {"type": "integer", "enum": [0, 1]},
                "tags": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Optional tags, appended to content when provided",
                },
            },
            "required": ["diary_id"],
            "additionalProperties": False,
        },
        executor=_exec_update_diary,
    ),
    "create_todo": ToolDefinition(
        name="create_todo",
        description="Create a todo item for the current user.",
        mode="operation",
        requires_confirmation=True,
        display_name="创建待办",
        parameters={
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "todo_date": {"type": "string", "description": "Date in YYYY-MM-DD"},
                "note": {"type": "string"},
            },
            "required": ["title"],
            "additionalProperties": False,
        },
        executor=_exec_create_todo,
    ),
    "search_diaries": ToolDefinition(
        name="search_diaries",
        description="Search diary entries by text keyword.",
        mode="read_only",
        requires_confirmation=False,
        display_name="搜索日记",
        parameters={
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 20},
                "offset": {"type": "integer", "minimum": 0},
            },
            "required": ["query"],
            "additionalProperties": False,
        },
        executor=_exec_search_diaries,
    ),
    "get_recent_summaries": ToolDefinition(
        name="get_recent_summaries",
        description="Get recent diary summaries for the current user.",
        mode="read_only",
        requires_confirmation=False,
        display_name="查询摘要",
        parameters={
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "minimum": 1, "maximum": 20},
            },
            "additionalProperties": False,
        },
        executor=_exec_get_recent_summaries,
    ),
    "open_white_noise": ToolDefinition(
        name="open_white_noise",
        description="Ask the client app to open white noise page.",
        mode="client_action",
        requires_confirmation=False,
        display_name="打开白噪音",
        parameters={"type": "object", "properties": {}, "additionalProperties": False},
        executor=_exec_open_white_noise,
    ),
    "open_test_hub": ToolDefinition(
        name="open_test_hub",
        description="Ask the client app to open test hub page.",
        mode="client_action",
        requires_confirmation=False,
        display_name="打开测试中心",
        parameters={"type": "object", "properties": {}, "additionalProperties": False},
        executor=_exec_open_test_hub,
    ),
    "open_drawing": ToolDefinition(
        name="open_drawing",
        description="Ask the client app to open drawing page.",
        mode="client_action",
        requires_confirmation=False,
        display_name="去画画",
        parameters={
            "type": "object",
            "properties": {
                "diary_id": {"type": "integer"},
            },
            "additionalProperties": False,
        },
        executor=_exec_open_drawing,
    ),
    "open_diary_editor": ToolDefinition(
        name="open_diary_editor",
        description="Ask the client app to open diary editor page.",
        mode="client_action",
        requires_confirmation=False,
        display_name="打开日记编辑页",
        parameters={
            "type": "object",
            "properties": {
                "diary_id": {"type": "integer"},
                "diary_date": {"type": "string", "description": "Date in YYYY-MM-DD"},
            },
            "additionalProperties": False,
        },
        executor=_exec_open_diary_editor,
    ),
    "open_todo_create": ToolDefinition(
        name="open_todo_create",
        description="Ask the client app to open the todo creation page.",
        mode="client_action",
        requires_confirmation=False,
        display_name="打开待办创建页",
        parameters={
            "type": "object",
            "properties": {
                "todo_date": {"type": "string", "description": "Date in YYYY-MM-DD (defaults to today)"},
            },
            "additionalProperties": False,
        },
        executor=_exec_open_todo_create,
    ),
}


def get_tool_definition(tool_name: str) -> Optional[ToolDefinition]:
    return TOOL_REGISTRY.get(tool_name)


def get_tool_schemas() -> List[Dict[str, Any]]:
    return [tool.openai_schema() for tool in TOOL_REGISTRY.values()]


async def execute_tool(
    tool_name: str,
    arguments: Dict[str, Any],
    db: AsyncSession,
    user_id: int,
    conversation_id: str,
) -> ToolExecutionResult:
    definition = get_tool_definition(tool_name)
    if not definition:
        raise ValueError(f"unknown tool: {tool_name}")

    return await definition.executor(arguments, db, user_id, conversation_id)


def build_tool_plan_preview(tool_name: str, arguments: Dict[str, Any]) -> str:
    if tool_name == "create_diary":
        content = str(arguments.get("content", "")).strip()
        return content[:120]
    if tool_name == "update_diary":
        diary_id = arguments.get("diary_id")
        return f"diary_id={diary_id}"
    if tool_name == "create_todo":
        title = str(arguments.get("title", "")).strip()
        return title[:120]
    return json.dumps(arguments, ensure_ascii=False)[:160]

