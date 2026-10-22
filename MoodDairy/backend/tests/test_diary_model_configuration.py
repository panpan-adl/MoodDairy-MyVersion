"""Keep diary extraction and speech polishing on the configured model endpoint."""
import httpx
import pytest

from app.services.external_api_client import ExternalAPIClient, QianfanClient


def test_extraction_prompt_example_satisfies_the_persistence_schema():
    import json
    from app.models.schemas import ExtractionResultSchema
    prompt = QianfanClient.EXTRACTION_PROMPT_TEMPLATE.format(
        diary_content='合成测试', diary_date='2026-10-22', weather='晴', location='公园', mood_score=75)
    example = json.loads(prompt[prompt.index('{'):prompt.rindex('}') + 1])
    ExtractionResultSchema.model_validate(example)


def test_configured_ark_endpoint_model_and_key_stay_together(monkeypatch):
    monkeypatch.setenv('ARK_API_KEY', 'synthetic-ark-key')
    monkeypatch.setenv('ARK_BASE_URL', 'https://configured.example/api/v3/')
    monkeypatch.setenv('ARK_MODEL_NAME', 'configured-model')
    monkeypatch.setenv('QIANFAN_API_KEY', 'synthetic-legacy-key')
    monkeypatch.delenv('ARK_TRUST_ENV_PROXY', raising=False)
    client = ExternalAPIClient()._qianfan_client
    assert client.api_key == 'synthetic-ark-key'
    assert client.chat_url == 'https://configured.example/api/v3/chat/completions'
    assert client.model == 'configured-model'
    assert client.trust_env is False


def test_qianfan_only_configuration_remains_supported(monkeypatch):
    monkeypatch.delenv('ARK_API_KEY', raising=False)
    monkeypatch.setenv('QIANFAN_API_KEY', 'synthetic-legacy-key')
    monkeypatch.setenv('QIANFAN_MODEL_NAME', 'legacy-model')
    client = ExternalAPIClient()._qianfan_client
    assert client.api_key == 'synthetic-legacy-key'
    assert client.chat_url == QianfanClient.CHAT_URL
    assert client.model == 'legacy-model'


@pytest.mark.asyncio
async def test_extraction_and_polishing_send_configured_model(monkeypatch):
    calls = []
    async def respond(request):
        import json
        calls.append((str(request.url), json.loads(request.content), request.headers['Authorization']))
        return httpx.Response(200, json={'choices': [{'message': {'content': '优化后的测试文字'}}]})
    client_factory = httpx.AsyncClient
    monkeypatch.setattr('app.services.external_api_client.httpx.AsyncClient',
        lambda **kwargs: client_factory(transport=httpx.MockTransport(respond), **kwargs))
    client = QianfanClient('synthetic-key', chat_url='https://configured.example/chat/completions',
                          model='configured-model', trust_env=False)
    await client._call_llm([{'role': 'user', 'content': '合成测试'}])
    assert await client.text_optimization('合成测试') == '优化后的测试文字'
    assert len(calls) == 2
    assert all(url == client.chat_url and body['model'] == 'configured-model'
               and auth == 'Bearer synthetic-key' for url, body, auth in calls)
