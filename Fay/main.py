from __future__ import annotations

import asyncio
import json
import base64

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse, Response
from fastapi.middleware.cors import CORSMiddleware

from config import config
from pipeline import process_text_input
from omni_pipeline import omni_manager
from session_manager import SessionManager
from models import WsInType, WsOutType

app = FastAPI(title="Fay Digital Human", version="2.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def health():
    return {"status": "ok", "service": "fay-digital-human", "tts": "qwen3-omni"}


# ── SSE 文本流（兼容旧前端）──────────────────────────────────────

@app.get("/api/agent/ping")
async def agent_ping(sessionId: str, message: str):
    async def sse_gen():
        async for chunk in process_text_input(sessionId, message):
            if chunk["type"] == "text_chunk":
                yield f"data: {chunk['data']}\n\n"

    return StreamingResponse(
        sse_gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.post("/api/agent/new")
async def agent_new(sessionId: str):
    SessionManager.get().clear(sessionId)
    omni_manager.remove(sessionId)
    return Response(status_code=204)


# ── WebSocket — Qwen3-Omni 实时语音对话 ──────────────────────────

@app.websocket("/ws/chat")
async def ws_chat(ws: WebSocket, session_id: str = ""):
    await ws.accept()
    sid = session_id or "ws-default"
    outbox: asyncio.Queue = asyncio.Queue()
    pipe = omni_manager.get_or_create(sid, outbox)

    async def _outbox_loop():
        """把 Omni 回调推到前端。"""
        while True:
            msg = await outbox.get()
            try:
                await ws.send_json(msg)
            except Exception:
                break

    outbox_task = asyncio.create_task(_outbox_loop())

    try:
        while True:
            raw = await ws.receive_text()
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await ws.send_json({"type": "error", "data": "invalid json"})
                continue

            msg_type = msg.get("type", "")
            msg_data = msg.get("data", "")

            if msg_type == "ping":
                await ws.send_json({"type": "pong"})

            elif msg_type == "text":
                # 文本消息走 SSE 管线（llm_client），避免 Omni Realtime 异步时序问题
                async for chunk in process_text_input(sid, msg_data):
                    await outbox.put(chunk)

            elif msg_type == "audio":
                # base64 PCM 16kHz
                pipe.send_audio(msg_data)

            elif msg_type == "audio_done":
                pipe.commit_audio()

            elif msg_type == "cancel":
                pipe.cancel()

            else:
                await ws.send_json({"type": "error", "data": f"unknown type: {msg_type}"})

    except WebSocketDisconnect:
        pass
    finally:
        outbox_task.cancel()
        # 不关闭 Omni 连接，允许重连复用


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=config.SERVER_PORT, reload=True)
