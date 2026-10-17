from unittest.mock import AsyncMock
import httpx
import pytest
from fastapi import FastAPI
from pydantic import ValidationError
from app.routers.insights import router
from app.security.deps import get_current_user_id
from app.services import diary_title_service as service


@pytest.mark.asyncio
async def test_recommendation_is_authenticated_and_does_not_save(monkeypatch):
    model = AsyncMock(return_value=service.TitleRecommendationResponse(title='忙碌清晨的一碗豆浆'))
    monkeypatch.setattr(service, 'generate_json', model)
    app = FastAPI()
    app.include_router(router)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        denied = await client.post('/insights/title-recommendation', json={'text': '清晨起床，买了豆浆去上课。'})
        assert denied.status_code in (401, 403)
        model.assert_not_awaited()
        app.dependency_overrides[get_current_user_id] = lambda: 1
        response = await client.post('/insights/title-recommendation', json={'text': '清晨起床，买了豆浆去上课。'})
        assert response.status_code == 200
        assert response.json() == {'title': '忙碌清晨的一碗豆浆'}
        assert model.await_args.args[1] == {'diary_text': '清晨起床，买了豆浆去上课。'}
        # No database dependency is installed: recommendations must not save drafts.
        for text in ('  ', 'x' * 12001):
            assert (await client.post('/insights/title-recommendation', json={'text': text})).status_code == 422
        assert model.await_count == 1


@pytest.mark.asyncio
async def test_model_failure_returns_actionable_generic_error(monkeypatch):
    monkeypatch.setattr(service, 'generate_json', AsyncMock(side_effect=RuntimeError('private model output')))
    with pytest.raises(Exception) as error:
        await service.recommend_title('今天去上课。')
    assert error.value.status_code == 503
    assert '直接保存' in error.value.detail
    assert 'private' not in error.value.detail


def test_title_is_clean_and_single_line():
    assert service.TitleRecommendationResponse(title=' “清晨的豆浆” ').title == '清晨的豆浆'
    for title in (' ', '第一行\n第二行', '长' * 31):
        with pytest.raises(ValidationError):
            service.TitleRecommendationResponse(title=title)
