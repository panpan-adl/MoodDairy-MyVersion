from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from app.models.schemas import UpdateUserRequest
from app.services.user_service import UserService


def test_signature_length_limit():
    assert UpdateUserRequest(signature="好" * 100).signature == "好" * 100
    with pytest.raises(ValidationError):
        UpdateUserRequest(signature="好" * 101)


@pytest.mark.asyncio
async def test_signature_save_clear_and_unrelated_edit(mock_db_session):
    user = SimpleNamespace(signature="原签名", nickname="昵称")
    service = UserService(mock_db_session)
    service.get_user = AsyncMock(return_value=user)

    await service.update_user(1, UpdateUserRequest(signature="慢慢来，也很好"))
    assert user.signature == "慢慢来，也很好"
    await service.update_user(1, UpdateUserRequest(nickname="新昵称"))
    assert user.signature == "慢慢来，也很好"
    await service.update_user(1, UpdateUserRequest(signature=""))
    assert user.signature == ""
    assert mock_db_session.commit.await_count == 3
