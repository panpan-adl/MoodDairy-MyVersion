"""Pydantic models for todo endpoints."""

from __future__ import annotations

from datetime import date, datetime
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field


class CreateTodoRequest(BaseModel):
    """Create todo request."""

    user_id: int = Field(..., description="User ID")
    todo_date: date = Field(..., description="Todo date")
    title: str = Field(..., min_length=1, max_length=200, description="Todo title")
    note: Optional[str] = Field(None, description="Todo note")
    sort_order: Optional[int] = Field(None, ge=0, description="Order in one day")


class UpdateTodoRequest(BaseModel):
    """Update todo request."""

    title: Optional[str] = Field(None, min_length=1, max_length=200, description="Todo title")
    note: Optional[str] = Field(None, description="Todo note")
    is_done: Optional[int] = Field(None, ge=0, le=1, description="Done flag")
    sort_order: Optional[int] = Field(None, ge=0, description="Order in one day")


class TodoResponse(BaseModel):
    """Todo response."""

    id: int = Field(..., description="Todo ID")
    user_id: int = Field(..., description="User ID")
    todo_date: date = Field(..., description="Todo date")
    title: str = Field(..., description="Todo title")
    note: Optional[str] = Field(None, description="Todo note")
    is_done: int = Field(..., description="Done flag (0/1)")
    sort_order: int = Field(..., description="Order in one day")
    created_at: datetime = Field(..., description="Created time")
    updated_at: datetime = Field(..., description="Updated time")

    model_config = ConfigDict(from_attributes=True)
