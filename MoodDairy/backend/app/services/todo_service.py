"""Todo service layer."""

from __future__ import annotations

from datetime import date
from typing import List, Optional

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.database import Todo
from app.models.todo_schemas import CreateTodoRequest, UpdateTodoRequest


class TodoService:
    """Service for todo CRUD operations."""

    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_todo(self, data: CreateTodoRequest) -> Todo:
        """Create a todo item."""

        sort_order = data.sort_order
        if sort_order is None:
            stmt = (
                select(func.coalesce(func.max(Todo.sort_order), -1))
                .where(
                    and_(
                        Todo.user_id == data.user_id,
                        Todo.todo_date == data.todo_date,
                    )
                )
            )
            result = await self.db.execute(stmt)
            sort_order = int(result.scalar() or -1) + 1

        todo = Todo(
            user_id=data.user_id,
            todo_date=data.todo_date,
            title=data.title.strip(),
            note=data.note,
            sort_order=sort_order,
            is_done=0,
        )
        self.db.add(todo)
        await self.db.commit()
        await self.db.refresh(todo)
        return todo

    async def get_todos_by_date(self, user_id: int, todo_date: date) -> List[Todo]:
        """Get all todos for one day."""

        stmt = (
            select(Todo)
            .where(
                and_(
                    Todo.user_id == user_id,
                    Todo.todo_date == todo_date,
                )
            )
            .order_by(Todo.is_done.asc(), Todo.sort_order.asc(), Todo.created_at.asc())
        )
        result = await self.db.execute(stmt)
        return list(result.scalars().all())

    async def get_todo_by_id(self, todo_id: int) -> Optional[Todo]:
        """Get one todo by id."""

        result = await self.db.execute(select(Todo).where(Todo.id == todo_id))
        return result.scalar_one_or_none()

    async def update_todo(self, todo_id: int, user_id: int, data: UpdateTodoRequest) -> Optional[Todo]:
        """Update a todo item if it belongs to the user."""

        todo = await self.get_todo_by_id(todo_id)
        if not todo or todo.user_id != user_id:
            return None

        update_data = data.model_dump(exclude_unset=True)
        for field, value in update_data.items():
            if field == "title" and value is not None:
                value = value.strip()
            setattr(todo, field, value)

        await self.db.commit()
        await self.db.refresh(todo)
        return todo

    async def delete_todo(self, todo_id: int, user_id: int) -> bool:
        """Delete one todo item if it belongs to the user."""

        todo = await self.get_todo_by_id(todo_id)
        if not todo or todo.user_id != user_id:
            return False

        await self.db.delete(todo)
        await self.db.commit()
        return True
