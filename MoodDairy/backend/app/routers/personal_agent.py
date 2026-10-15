"""Authenticated personal agent endpoints, sharing the existing todos and diaries."""
from datetime import date as date_type, datetime
from zoneinfo import ZoneInfo
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_db
from app.security.deps import AuthenticatedUserId
from app.models.personal_agent_schemas import (PlanRequest, PlanDraft, AdoptPlanRequest, TodayResponse,
    AgentTask, TaskUpdate, ReviewRequest, ReviewDraft, SaveReviewRequest, SavedDiary, SaveRecordRequest)
from app.services.personal_agent_service import PersonalAgentService, task_view

router = APIRouter(prefix='/agent', tags=['personal-agent'])

@router.get('/tasks/history', response_model=list[AgentTask])
async def task_history(current_user_id: AuthenticatedUserId, db: AsyncSession = Depends(get_db)):
    tasks = await PersonalAgentService(db).task_history(current_user_id, datetime.now(ZoneInfo('Asia/Shanghai')).date())
    return [task_view(task) for task in tasks]

@router.get('/today', response_model=TodayResponse)
async def today(date: date_type, current_user_id: AuthenticatedUserId, db: AsyncSession = Depends(get_db)):
    tasks = await PersonalAgentService(db).todos(current_user_id, date)
    return TodayResponse(date=date, tasks=[task_view(t) for t in tasks])

@router.post('/plan', response_model=PlanDraft)
async def plan(request: PlanRequest, current_user_id: AuthenticatedUserId, db: AsyncSession = Depends(get_db)):
    return await PersonalAgentService(db).plan(current_user_id, request)

@router.post('/adopt', response_model=TodayResponse)
async def adopt(request: AdoptPlanRequest, current_user_id: AuthenticatedUserId, db: AsyncSession = Depends(get_db)):
    if request.date > datetime.now(ZoneInfo('Asia/Shanghai')).date():
        raise HTTPException(422, '今日记录不能保存到未来日期')
    tasks = await PersonalAgentService(db).adopt(current_user_id, request)
    return TodayResponse(date=request.date, tasks=[task_view(t) for t in tasks])

@router.patch('/tasks/{task_id}', response_model=AgentTask)
async def update_task(task_id: int, request: TaskUpdate, current_user_id: AuthenticatedUserId, db: AsyncSession = Depends(get_db)):
    return await PersonalAgentService(db).update(current_user_id, task_id, request)

@router.post('/review', response_model=ReviewDraft)
async def review(request: ReviewRequest, current_user_id: AuthenticatedUserId, db: AsyncSession = Depends(get_db)):
    return await PersonalAgentService(db).review(current_user_id, request)

@router.post('/review/save', response_model=SavedDiary)
async def save_review(request: SaveReviewRequest, current_user_id: AuthenticatedUserId, db: AsyncSession = Depends(get_db)):
    if request.date > datetime.now(ZoneInfo('Asia/Shanghai')).date():
        raise HTTPException(422, '复盘不能保存到未来日期')
    diary_id = await PersonalAgentService(db).save_review(current_user_id, request)
    return SavedDiary(diary_id=diary_id)

@router.post('/record', response_model=SavedDiary)
async def save_record(request: SaveRecordRequest, current_user_id: AuthenticatedUserId, db: AsyncSession = Depends(get_db)):
    if request.date > datetime.now(ZoneInfo('Asia/Shanghai')).date():
        raise HTTPException(422, '记录不能保存到未来日期')
    diary_id = await PersonalAgentService(db).save_record(current_user_id, request)
    return SavedDiary(diary_id=diary_id)
