"""Personal-agent regressions with real ORM queries against an isolated SQLite database.
Postgres advisory locking is bypassed here; retry behavior and account scoping are exercised.
"""
from datetime import date, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI, HTTPException
import httpx
from pydantic import ValidationError
from sqlalchemy import BigInteger, create_engine, select
from sqlalchemy.ext.compiler import compiles
from sqlalchemy.orm import Session

from app.models.database import User, Diary, Todo
from app.models.personal_agent_schemas import (TaskDraft, PlanDraft, PlanRequest, AdoptPlanRequest,
    TaskUpdate, ReviewRequest, SaveReviewRequest)
from app.services import personal_agent_service as agent
from app.routers.personal_agent import router
from app.database import get_db
from app.security.deps import get_current_user_id
from app.security.field_crypto import decrypt_field

@compiles(BigInteger, 'sqlite')
def sqlite_bigint(element, compiler, **kw):
    return 'INTEGER'

class AsyncSessionAdapter:
    def __init__(self, session):
        self.session = session
    async def execute(self, stmt, params=None):
        if 'pg_advisory_xact_lock' in str(stmt):
            return None
        return self.session.execute(stmt, params or {})
    def add(self, row):
        self.session.add(row)
    async def commit(self):
        self.session.commit()
    async def refresh(self, row):
        self.session.refresh(row)

@pytest.fixture
def db():
    engine = create_engine('sqlite://')
    for table in (User.__table__, Diary.__table__, Todo.__table__):
        table.create(engine)
    with Session(engine, expire_on_commit=False) as session:
        session.add_all([User(id=1, username='one', password='test'), User(id=2, username='two', password='test')])
        session.commit()
        yield AsyncSessionAdapter(session)
    engine.dispose()

def request(**kwargs):
    data = dict(date=date.today(), record='写工作方案，学习英语，晚饭后散步',
                request_id=uuid4(), tasks=[TaskDraft(title='工作方案', domain='work', minutes=30, next_step='先列提纲')])
    data.update(kwargs)
    return AdoptPlanRequest(**data)

@pytest.mark.asyncio
async def test_historical_legacy_tasks_remain_scoped_and_can_move_to_today(db):
    day = date.today()
    db.add(Todo(user_id=1, todo_date=day-timedelta(days=2), title='旧待办', note='先读两页', is_done=False))
    db.add(Todo(user_id=2, todo_date=day-timedelta(days=2), title='其他用户的任务', is_done=False))
    db.add(Todo(user_id=1, todo_date=day, title='今天的任务', is_done=False))
    await db.commit()
    service = agent.PersonalAgentService(db)
    history = await service.task_history(1, day)
    assert [task.title for task in history] == ['旧待办']
    assert agent.task_view(history[0]).next_step == '先读两页'
    updated = await service.update(1, history[0].id, TaskUpdate(date=day))
    assert updated.date == day
    assert await service.task_history(1, day) == []
    assert len(await service.todos(1, day)) == 2

@pytest.mark.parametrize('energy,budget,expected', [('low', 180, 1), ('normal', 180, 3), ('normal', 30, 0)])
def test_budget_and_energy_defer_overflow_without_changing_estimates(energy, budget, expected):
    plan = PlanDraft(suggestion='按精力安排', tasks=[TaskDraft(title=str(i), minutes=60) for i in range(3)])
    result = agent.fit_plan(plan, budget, energy)
    assert len(result.tasks) == expected
    assert all(t.minutes == 60 for t in result.tasks)

def test_blank_titles_and_invalid_domains_are_rejected():
    for payload in ({'title': '   '}, {'title': 'test', 'domain': 'unknown'}, {'title': 'test', 'minutes': 0}):
        with pytest.raises(ValidationError):
            TaskDraft(**payload)

def test_existing_plain_todos_are_readable():
    todo = SimpleNamespace(id=1, title='买菜', note='买西红柿', is_done=0, todo_date=date.today())
    result = agent.task_view(todo)
    assert result.title == '买菜' and result.domain == 'life' and result.next_step == '买西红柿'

@pytest.mark.asyncio
async def test_adoption_is_atomic_and_retry_does_not_duplicate(db):
    service = agent.PersonalAgentService(db)
    req = request()
    await service.adopt(1, req)
    await service.adopt(1, req)
    todos = await service.todos(1, req.date)
    diaries = db.session.scalars(select(Diary)).all()
    assert len(todos) == 1 and len(diaries) == 1
    assert agent.task_view(todos[0]).domain == 'work'
    assert agent.task_view(todos[0]).minutes == 30
    assert diaries[0].is_private == 1
    assert decrypt_field(diaries[0].content) == req.record

@pytest.mark.asyncio
async def test_same_title_is_not_readded_in_another_plan(db):
    service = agent.PersonalAgentService(db)
    await service.adopt(1, request())
    await service.adopt(1, request())
    assert len(await service.todos(1, date.today())) == 1

@pytest.mark.asyncio
async def test_completion_edit_and_postpone_are_persisted(db):
    service = agent.PersonalAgentService(db)
    todo = (await service.adopt(1, request()))[0]
    updated = await service.update(1, todo.id, TaskUpdate(title='整理方案提纲', is_done=True, domain='study', minutes=15))
    assert updated.is_done and updated.domain == 'study' and updated.title == '整理方案提纲'
    tomorrow = date.today() + timedelta(days=1)
    await service.update(1, todo.id, TaskUpdate(date=tomorrow, is_done=False))
    assert await service.todos(1, date.today()) == []
    assert len(await service.todos(1, tomorrow)) == 1

@pytest.mark.asyncio
async def test_other_user_cannot_read_or_mutate_tasks(db):
    service = agent.PersonalAgentService(db)
    todo = (await service.adopt(2, request()))[0]
    assert await service.todos(1, date.today()) == []
    with pytest.raises(HTTPException) as exc:
        await service.update(1, todo.id, TaskUpdate(is_done=True))
    assert exc.value.status_code == 404
    assert not todo.is_done

@pytest.mark.asyncio
async def test_plan_accounts_for_existing_task_time_and_excludes_duplicates(db, monkeypatch):
    service = agent.PersonalAgentService(db)
    await service.adopt(1, request())
    generate = AsyncMock(return_value=PlanDraft(suggestion='test', tasks=[TaskDraft(title='工作方案', minutes=10), TaskDraft(title='英语', domain='study', minutes=30)]))
    monkeypatch.setattr(agent, 'generate_json', generate)
    result = await service.plan(1, PlanRequest(date=date.today(), record='学习英语', available_minutes=60))
    assert [t.title for t in result.tasks] == ['英语']
    assert generate.call_args.args[1]['available_minutes'] == 30

@pytest.mark.asyncio
async def test_review_uses_owned_records_and_real_completion(db, monkeypatch):
    service = agent.PersonalAgentService(db)
    todo = (await service.adopt(1, request()))[0]
    await service.adopt(2, request(record='他人的私密记录'))
    await service.update(1, todo.id, TaskUpdate(is_done=True))
    generate = AsyncMock(return_value=agent.ReviewDraft(content='今天完成了工作方案。'))
    monkeypatch.setattr(agent, 'generate_json', generate)
    await service.review(1, ReviewRequest(date=date.today(), reflection='有点累'))
    context = generate.call_args.args[1]
    assert context['tasks'][0]['is_done'] is True
    assert context['reflection'] == '有点累'
    assert all('他人' not in d['content'] for d in context['recent_records'])

@pytest.mark.asyncio
async def test_review_save_retry_is_idempotent_and_private(db):
    service = agent.PersonalAgentService(db)
    req = SaveReviewRequest(date=date.today(), request_id=uuid4(), content='今日复盘')
    first = await service.save_review(1, req)
    assert await service.save_review(1, req) == first
    diary = db.session.get(Diary, first)
    assert diary.is_private == 1 and decrypt_field(diary.content) == req.content

@pytest.mark.asyncio
async def test_missing_provider_is_a_clear_failure(monkeypatch):
    monkeypatch.delenv('ARK_API_KEY', raising=False)
    with pytest.raises(HTTPException) as exc:
        await agent.generate_json('test', {}, PlanDraft)
    assert exc.value.status_code == 503 and '手动' in exc.value.detail

@pytest.mark.asyncio
async def test_explicit_null_and_whitespace_update_are_rejected(db):
    service = agent.PersonalAgentService(db)
    todo = (await service.adopt(1, request()))[0]
    for req in (TaskUpdate(is_done=None), TaskUpdate(title='   ')):
        with pytest.raises(HTTPException) as exc:
            await service.update(1, todo.id, req)
        assert exc.value.status_code == 422


@pytest.mark.asyncio
async def test_agent_routes_require_login_before_database_access():
    app = FastAPI()
    app.include_router(router)
    async def unused_db():
        yield None
    app.dependency_overrides[get_db] = unused_db
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        response = await client.get('/agent/today', params={'date': str(date.today())})
    assert response.status_code == 401

@pytest.mark.asyncio
async def test_record_only_save_creates_no_tasks_and_retry_is_idempotent(db):
    from app.models.personal_agent_schemas import SaveRecordRequest
    service = agent.PersonalAgentService(db)
    req = SaveRecordRequest(date=date.today(), record='今天有些疲惫，想记录一下。', request_id=uuid4())
    diary_id = await service.save_record(1, req)
    assert await service.save_record(1, req) == diary_id
    assert await service.todos(1, date.today()) == []
    diary = db.session.get(Diary, diary_id)
    assert diary.is_private == 1 and decrypt_field(diary.content) == req.record

@pytest.mark.asyncio
async def test_record_and_review_request_ids_are_scoped_by_operation(db):
    from app.models.personal_agent_schemas import SaveRecordRequest
    service = agent.PersonalAgentService(db)
    token = uuid4()
    record_id = await service.save_record(1, SaveRecordRequest(date=date.today(), record='今日记录', request_id=token))
    review_id = await service.save_review(1, SaveReviewRequest(date=date.today(), content='晚间复盘', request_id=token))
    assert record_id != review_id

@pytest.mark.asyncio
async def test_failed_adoption_can_rollback_both_record_and_tasks(db, monkeypatch):
    service = agent.PersonalAgentService(db)
    async def fail_commit():
        db.session.flush()
        raise RuntimeError('simulated database failure')
    monkeypatch.setattr(db, 'commit', fail_commit)
    with pytest.raises(RuntimeError):
        await service.adopt(1, request())
    db.session.rollback()
    assert db.session.scalars(select(Todo)).all() == []
    assert db.session.scalars(select(Diary)).all() == []
