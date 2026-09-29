"""Tests for the chat router configuration and helpers."""

import pytest


class TestChatRouterConfiguration:
    def test_router_can_be_imported(self):
        from app.routers.chat import router

        assert router is not None

    def test_router_prefix(self):
        from app.routers.chat import router

        assert router.prefix == "/chat"

    def test_router_tags(self):
        from app.routers.chat import router

        assert "chat" in router.tags


class TestChatEndpoints:
    def test_chat_endpoints_exist(self):
        from app.routers.chat import router

        routes = [route.path for route in router.routes]
        assert "/chat" in routes
        assert "/chat/" in routes
        assert "/chat/stream" in routes

    def test_chat_routes_are_post(self):
        from app.routers.chat import router

        matched = [route for route in router.routes if route.path in ("/chat", "/chat/", "/chat/stream")]
        assert matched
        for route in matched:
            assert "POST" in route.methods


class TestChatRequestModels:
    def test_chat_request_supports_image_inputs(self):
        from app.routers.chat import ChatRequest

        request = ChatRequest(
            user_id=1,
            message="look at this",
            conversation_id="conv_test",
            image_data_urls=["data:image/jpeg;base64,abc"],
        )

        assert request.image_data_urls == ["data:image/jpeg;base64,abc"]


class TestChatHelperFunctions:
    def test_build_context_with_empty_summaries(self):
        from app.routers.chat import _build_context

        result = _build_context([])
        assert "no recent diary summaries" in result.lower()

    def test_build_context_with_summaries(self):
        from app.routers.chat import DiarySummarySimple, _build_context

        summary = DiarySummarySimple(
            diary_id=1,
            diary_date="2026-01-18",
            summary="Today felt calm and warm.",
            keywords=["calm", "weather"],
            primary_emotion="happy",
            emotion_score=80,
        )

        result = _build_context([summary])
        assert "2026-01-18" in result
        assert "Today felt calm and warm." in result
        assert "happy" in result

    def test_encode_sse(self):
        from app.routers.chat import _encode_sse

        event = _encode_sse({"type": "delta", "delta": "hello"})
        assert event.startswith("data: ")
        assert event.endswith("\n\n")


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
