"""FastAPI application entrypoint for the diary backend."""

from __future__ import annotations

import os
from contextlib import asynccontextmanager

os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("TRANSFORMERS_NO_TF", "1")

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from app.database import check_db_connection, close_db, init_db
from app.drawing import drawing_router
from app.drawing.generator_config import GeneratorConfig
from app.routers import chat, diaries, insights, media, todos, users, voice
from app.routers import diary_search, diary_summaries
from app.security.middleware import JWTAuthMiddleware
from app.security.log_filter import install_sensitive_log_filter
from app.utils.error_handler import handle_exception

install_sensitive_log_filter()

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UPLOADS_DIR = os.path.join(BASE_DIR, "uploads")
MAX_UPLOAD_SIZE = 200 * 1024 * 1024

os.makedirs(UPLOADS_DIR, exist_ok=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage backend startup and shutdown."""

    del app

    print("Diary backend starting...")
    try:
        await init_db()
        if await check_db_connection():
            print("Database connection is ready.")
        else:
            print("Database connection check failed.")
    except Exception as exc:
        print(f"Database initialization error: {exc}")

    # 初始化绘图配置（从环境变量读取）
    GeneratorConfig.init_from_env()
    from app.drawing.generator_config import is_drawing_enabled, is_sd_enabled
    print(f"AI Drawing enabled: {is_drawing_enabled()}, SD enabled: {is_sd_enabled()}")

    yield

    print("Diary backend shutting down...")
    await close_db()
    print("Database connections closed.")


app = FastAPI(
    title="Diary API",
    description="Backend API for the smart diary system.",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(JWTAuthMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def connection_close_for_reverse_proxy(request: Request, call_next):
    """通过 adb reverse / 反向代理时避免连接复用导致客户端 unexpected end of stream."""
    response = await call_next(request)
    if "connection" not in response.headers:
        response.headers["Connection"] = "close"
    return response


@app.middleware("http")
async def validate_file_size_middleware(request: Request, call_next):
    """Reject oversized media upload requests before body processing."""

    if request.url.path.startswith("/media/upload") or request.url.path.startswith("/api/media/upload"):
        content_length = request.headers.get("content-length")
        if content_length:
            content_length_value = int(content_length)
            if content_length_value > MAX_UPLOAD_SIZE:
                return JSONResponse(
                    status_code=413,
                    content={
                        "detail": (
                            f"File size {content_length_value / 1024 / 1024:.2f}MB exceeds the "
                            f"limit of {MAX_UPLOAD_SIZE / 1024 / 1024:.0f}MB."
                        ),
                        "error_code": "FILE_TOO_LARGE",
                        "max_size_mb": MAX_UPLOAD_SIZE / 1024 / 1024,
                        "actual_size_mb": content_length_value / 1024 / 1024,
                    },
                )

    return await call_next(request)


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """Return a unified error response for uncaught exceptions."""

    return await handle_exception(request, exc)


@app.get("/")
async def root():
    """Basic service status endpoint."""

    return {"message": "Diary API", "status": "running", "version": "1.0.0"}


@app.get("/health")
async def health_check():
    """Health check endpoint."""

    db_status = "connected" if await check_db_connection() else "disconnected"
    return {"status": "healthy", "database": db_status}


app.include_router(diary_summaries.router)
app.include_router(diary_search.router)
app.include_router(diaries.router)
app.include_router(insights.router)
app.include_router(media.router)
app.include_router(voice.router)
app.include_router(users.router)
app.include_router(chat.router)
app.include_router(todos.router)
app.include_router(drawing_router)

app.mount("/media", StaticFiles(directory=UPLOADS_DIR), name="media")
app.mount("/uploads", StaticFiles(directory=UPLOADS_DIR), name="uploads_legacy")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
