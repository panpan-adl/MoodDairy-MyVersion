"""Contracts for the personal agent; no client-supplied identity is trusted."""
from datetime import date as date_type
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, field_validator

Domain = Literal['work', 'study', 'life']
Energy = Literal['low', 'normal', 'high']

class TaskDraft(BaseModel):
    title: str = Field(min_length=1, max_length=180)
    domain: Domain = 'life'
    minutes: int = Field(default=25, ge=5, le=240)
    next_step: str = Field(default='', max_length=500)

    @field_validator('title')
    @classmethod
    def nonblank(cls, value):
        value = value.strip()
        if not value:
            raise ValueError('任务名称不能为空')
        return value

class PlanRequest(BaseModel):
    date: date_type
    record: str = Field(min_length=1, max_length=6000)
    energy: Energy = 'normal'
    available_minutes: int = Field(default=120, ge=15, le=480)

    @field_validator('record')
    @classmethod
    def nonblank(cls, value):
        value = value.strip()
        if not value:
            raise ValueError('请先记录今天的想法')
        return value

class PlanDraft(BaseModel):
    suggestion: str = Field(min_length=1, max_length=1500)
    tasks: list[TaskDraft] = Field(default_factory=list, max_length=6)

class AdoptPlanRequest(PlanRequest):
    request_id: UUID
    tasks: list[TaskDraft] = Field(min_length=1, max_length=6)

class AgentTask(BaseModel):
    id: int
    title: str
    domain: Domain
    minutes: int
    next_step: str
    is_done: bool
    date: date_type

class TodayResponse(BaseModel):
    date: date_type
    tasks: list[AgentTask]

class TaskUpdate(BaseModel):
    model_config = ConfigDict(extra='forbid')
    title: str | None = Field(default=None, min_length=1, max_length=180)
    domain: Domain | None = None
    minutes: int | None = Field(default=None, ge=5, le=240)
    next_step: str | None = Field(default=None, max_length=500)
    is_done: bool | None = None
    date: date_type | None = None

class ReviewRequest(BaseModel):
    date: date_type
    reflection: str = Field(default='', max_length=6000)

class ReviewDraft(BaseModel):
    content: str = Field(min_length=1, max_length=12000)

class SaveReviewRequest(ReviewDraft):
    date: date_type
    request_id: UUID

class SavedDiary(BaseModel):
    diary_id: int

class SaveRecordRequest(BaseModel):
    date: date_type
    record: str = Field(min_length=1, max_length=6000)
    request_id: UUID

    @field_validator('record')
    @classmethod
    def nonblank(cls, value):
        value = value.strip()
        if not value:
            raise ValueError('记录不能为空')
        return value