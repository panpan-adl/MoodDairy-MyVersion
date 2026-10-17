"""ASGI request correlation without buffering bodies or logging private content."""
import asyncio
import logging
import re
import time
from uuid import uuid4

logger = logging.getLogger(__name__)


class RequestDiagnosticsMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope['path'] == '/health':
            return await self.app(scope, receive, send)
        supplied = dict(scope.get('headers', [])).get(b'x-request-id', b'').decode('ascii', errors='ignore')
        request_id = supplied if re.fullmatch(r'[A-Za-z0-9_-]{1,64}', supplied) else uuid4().hex
        start = time.monotonic()
        status = None
        completed = False
        disconnected = False

        def record(event, **extra):
            logger.info('request.%s request_id=%s method=%s path=%s status=%s elapsed_ms=%d %s',
                        event, request_id, scope['method'], scope['path'], status,
                        int((time.monotonic() - start) * 1000), extra)

        async def traced_receive():
            nonlocal disconnected
            message = await receive()
            if message['type'] == 'http.disconnect':
                disconnected = True
            return message

        async def traced_send(message):
            nonlocal status, completed
            if message['type'] == 'http.response.start':
                status = message['status']
                message = dict(message, headers=[(k, v) for k, v in message.get('headers', [])
                                                if k.lower() != b'x-request-id'] +
                               [(b'x-request-id', request_id.encode('ascii'))])
            await send(message)
            if message['type'] == 'http.response.body' and not message.get('more_body', False):
                completed = True

        record('start')
        try:
            await self.app(scope, traced_receive, traced_send)
        except (Exception, asyncio.CancelledError) as exc:
            record('failed', error_type=type(exc).__name__, response_started=status is not None)
            raise
        else:
            # Sending a response is not proof that the client received it.
            record('response_sent' if completed else 'incomplete', disconnected=disconnected)
