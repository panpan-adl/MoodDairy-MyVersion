"""Tests for the diary summaries router."""

import pytest


class TestDiarySummariesRouter:
    def test_router_can_be_imported(self):
        from app.routers.diary_summaries import router

        assert router is not None

    def test_summaries_endpoint_exists(self):
        from app.routers.diary_summaries import router

        routes = [route.path for route in router.routes]
        assert "/diaries/summaries" in routes

    def test_router_prefix(self):
        from app.routers.diary_summaries import router

        assert router.prefix == "/diaries"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
