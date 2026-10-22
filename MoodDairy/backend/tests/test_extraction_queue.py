"""A Celery installation alone must not strand local diary extraction."""
from unittest.mock import MagicMock

from app import celery_app as queue


def test_uninstalled_queue_is_unavailable(monkeypatch):
    monkeypatch.setattr(queue, "CELERY_INSTALLED", False)
    assert queue.extraction_queue_available() is False


def test_unreachable_broker_is_unavailable(monkeypatch):
    app = MagicMock()
    app.connection_for_write.side_effect = ConnectionRefusedError()
    monkeypatch.setattr(queue, "celery_app", app)
    monkeypatch.setattr(queue, "CELERY_INSTALLED", True)
    assert queue.extraction_queue_available() is False
    app.control.inspect.assert_not_called()


def test_reachable_broker_requires_live_worker(monkeypatch):
    app = MagicMock()
    monkeypatch.setattr(queue, "celery_app", app)
    monkeypatch.setattr(queue, "CELERY_INSTALLED", True)
    app.control.inspect.return_value.ping.return_value = None
    assert queue.extraction_queue_available() is False
    app.control.inspect.return_value.ping.return_value = {"worker": {"ok": "pong"}}
    assert queue.extraction_queue_available() is True
    app.connection_for_write.assert_called_with(connect_timeout=1)
