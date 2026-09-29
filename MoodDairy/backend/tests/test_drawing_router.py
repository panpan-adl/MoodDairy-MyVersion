"""Tests for drawing router configuration."""

import pytest


class TestRouterConfiguration:
    def test_router_can_be_imported(self):
        from app.drawing.drawing_router import router

        assert router is not None

    def test_router_prefix(self):
        from app.drawing.drawing_router import router

        assert router.prefix == "/drawing"

    def test_router_tags(self):
        from app.drawing.drawing_router import router

        assert "drawing" in router.tags


class TestEndpointDefinitions:
    def test_all_endpoints_defined(self):
        from app.drawing.drawing_router import router

        routes = {route.path for route in router.routes}
        assert f"{router.prefix}/generate" in routes
        assert f"{router.prefix}/sketch-upload" in routes
        assert f"{router.prefix}/enhance" in routes
        assert f"{router.prefix}/from-diary/{{diary_id}}" in routes
        assert f"{router.prefix}/to-video" in routes
        assert f"{router.prefix}/transform-video" in routes
        assert f"{router.prefix}/video-status/{{task_id}}" in routes

    def test_endpoint_methods(self):
        from app.drawing.drawing_router import router

        route_methods = {route.path: route.methods for route in router.routes}
        assert "POST" in route_methods[f"{router.prefix}/generate"]
        assert "POST" in route_methods[f"{router.prefix}/sketch-upload"]
        assert "POST" in route_methods[f"{router.prefix}/enhance"]
        assert "POST" in route_methods[f"{router.prefix}/to-video"]
        assert "POST" in route_methods[f"{router.prefix}/transform-video"]
        assert "GET" in route_methods[f"{router.prefix}/video-status/{{task_id}}"]


class TestModuleIntegration:
    def test_drawing_module_exports(self):
        from app.drawing import DrawingService, VideoService, VolcengineClient, drawing_router

        assert VolcengineClient is not None
        assert DrawingService is not None
        assert VideoService is not None
        assert drawing_router is not None


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
