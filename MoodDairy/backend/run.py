"""Backend development entrypoint."""

import os

import uvicorn

os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("TRANSFORMERS_NO_TF", "1")


if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=os.getenv("BACKEND_RELOAD", "false").lower() == "true",
        timeout_keep_alive=5,
    )
