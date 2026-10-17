import logging

import pytest

from app.request_diagnostics import RequestDiagnosticsMiddleware


async def invoke(app, request_id=b'editor-123', send_failure=False):
    messages = []
    async def receive():
        return {'type': 'http.disconnect'}
    async def send(message):
        if send_failure and message['type'] == 'http.response.body':
            raise ConnectionResetError('peer closed')
        messages.append(message)
    scope = dict(type='http', path='/diaries/', method='POST',
                 headers=[(b'x-request-id', request_id), (b'authorization', b'private-token')],
                 query_string=b'private-query')
    await RequestDiagnosticsMiddleware(app)(scope, receive, send)
    return messages


@pytest.mark.asyncio
async def test_logs_correlate_request_and_complete_response_without_private_data(caplog):
    async def app(scope, receive, send):
        await send(dict(type='http.response.start', status=201, headers=[]))
        await send(dict(type='http.response.body', body=b'private-body'))
    with caplog.at_level(logging.INFO):
        messages = await invoke(app)
    assert (b'x-request-id', b'editor-123') in messages[0]['headers']
    assert 'request.start' in caplog.text and 'request.response_sent' in caplog.text
    assert 'editor-123' in caplog.text and 'status=201' in caplog.text
    assert all(word not in caplog.text for word in ('private-token', 'private-query', 'private-body'))


@pytest.mark.asyncio
async def test_invalid_request_id_is_replaced(caplog):
    async def app(scope, receive, send):
        await send(dict(type='http.response.start', status=200, headers=[]))
        await send(dict(type='http.response.body', body=b''))
    with caplog.at_level(logging.INFO):
        messages = await invoke(app, b'bad\r\nforged')
    headers = dict(messages[0]['headers'])
    assert len(headers[b'x-request-id']) == 32
    assert 'forged' not in caplog.text


@pytest.mark.asyncio
@pytest.mark.parametrize('send_failure', [False, True])
async def test_handler_or_response_failure_is_not_reported_as_complete(caplog, send_failure):
    async def app(scope, receive, send):
        if not send_failure:
            raise RuntimeError('private error detail')
        await send(dict(type='http.response.start', status=201, headers=[]))
        await send(dict(type='http.response.body', body=b'diary'))
    with caplog.at_level(logging.INFO), pytest.raises((RuntimeError, ConnectionResetError)):
        await invoke(app, send_failure=send_failure)
    assert 'request.failed' in caplog.text
    assert 'request.response_sent' not in caplog.text
    assert 'private error detail' not in caplog.text


@pytest.mark.asyncio
async def test_client_disconnect_is_recorded(caplog):
    async def app(scope, receive, send):
        await receive()
    with caplog.at_level(logging.INFO):
        await invoke(app)
    assert 'request.incomplete' in caplog.text and "'disconnected': True" in caplog.text
