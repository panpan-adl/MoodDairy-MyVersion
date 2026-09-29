"""Todo routes."""

from __future__ import annotations

from datetime import date as date_type
from typing import List

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.schemas import ErrorResponse
from app.models.todo_schemas import CreateTodoRequest, TodoResponse, UpdateTodoRequest
from app.services.todo_service import TodoService
from app.security.deps import AuthenticatedUserId, EffectiveUserId
from app.utils.error_handler import NotFoundError, ValidationError as AppValidationError

router = APIRouter(prefix="/todos", tags=["todos"])


@router.post(
    "/",
    response_model=TodoResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create todo",
    responses={
        201: {"description": "Todo created"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal error"},
    },
)
async def create_todo(
    request: CreateTodoRequest,
    current_user_id: AuthenticatedUserId,
    db: AsyncSession = Depends(get_db),
):
    request = request.model_copy(update={"user_id": current_user_id})
    service = TodoService(db)
    todo = await service.create_todo(request)
    return TodoResponse.model_validate(todo)


@router.get(
    "/",
    response_model=List[TodoResponse],
    summary="List todos by date",
    responses={
        200: {"description": "Todo list"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal error"},
    },
)
async def list_todos(
    user_id: EffectiveUserId,
    date: str,
    db: AsyncSession = Depends(get_db),
):
    try:
        todo_date = date_type.fromisoformat(date)
    except ValueError as exc:
        raise AppValidationError(
            f"Invalid date format: {date}",
            details={"date": date, "expected_format": "YYYY-MM-DD"},
        ) from exc

    service = TodoService(db)
    todos = await service.get_todos_by_date(user_id=user_id, todo_date=todo_date)
    return [TodoResponse.model_validate(todo) for todo in todos]


@router.put(
    "/{todo_id}",
    response_model=TodoResponse,
    summary="Update todo",
    responses={
        200: {"description": "Todo updated"},
        404: {"model": ErrorResponse, "description": "Todo not found"},
        400: {"model": ErrorResponse, "description": "Invalid request"},
        500: {"model": ErrorResponse, "description": "Internal error"},
    },
)
async def update_todo(
    todo_id: int,
    user_id: EffectiveUserId,
    request: UpdateTodoRequest,
    db: AsyncSession = Depends(get_db),
):
    service = TodoService(db)
    todo = await service.update_todo(todo_id=todo_id, user_id=user_id, data=request)
    if not todo:
        raise NotFoundError(
            f"Todo {todo_id} not found or does not belong to user {user_id}",
            details={"todo_id": todo_id, "user_id": user_id},
        )
    return TodoResponse.model_validate(todo)


@router.delete(
    "/{todo_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete todo",
    responses={
        204: {"description": "Todo deleted"},
        404: {"model": ErrorResponse, "description": "Todo not found"},
        500: {"model": ErrorResponse, "description": "Internal error"},
    },
)
async def delete_todo(
    todo_id: int,
    user_id: EffectiveUserId,
    db: AsyncSession = Depends(get_db),
):
    service = TodoService(db)
    success = await service.delete_todo(todo_id=todo_id, user_id=user_id)
    if not success:
        raise NotFoundError(
            f"Todo {todo_id} not found or does not belong to user {user_id}",
            details={"todo_id": todo_id, "user_id": user_id},
        )
    return None
