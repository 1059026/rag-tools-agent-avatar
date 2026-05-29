from __future__ import annotations

import asyncio
import base64
import json
import time
from typing import AsyncIterator

from dashscope.audio.qwen_omni import (
    OmniRealtimeConversation,
    OmniRealtimeCallback,
    MultiModality,
    AudioFormat,
)
import dashscope

from config import config
from tool_system import TOOL_MAP

dashscope.api_key = config.DASHSCOPE_API_KEY

OMNI_MODEL = config.OMNI_MODEL
OMNI_VOICE = config.OMNI_VOICE
OMNI_INSTRUCTIONS = "你是行业知识库专业助手。用中文回答，简洁直接。不要回答专业以外的问题。当用户询问值班表或事件数据时，调用对应工具获取数据，并把返回的标记字符串原样保留在回复中。"

# Function calling 工具定义（OpenAI Realtime 格式）
OMNI_TOOLS = [
    {
        "type": "function",
        "name": "showDutyList",
        "description": "显示当前值班表，包含值班人员姓名、岗位、联系电话、班次信息。当用户询问谁在值班、值班表、排班时调用。",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    {
        "type": "function",
        "name": "showEventData",
        "description": "显示事件数据面板，包含事件编号、类别、等级、状态、更新时间。当用户询问事件列表、警情、险情时调用。",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
]


class OmniPipeline:
    """管理一个前端会话 ↔ DashScope Omni 的 WebSocket 代理连接。"""

    def __init__(self, outbox: asyncio.Queue):
        self.outbox = outbox         # 发回给前端的消息队列
        self.conv: OmniRealtimeConversation | None = None
        self._text_buffer = ""

    def _callback(self):
        pipeline = self

        class _CB(OmniRealtimeCallback):
            def on_open(_self):
                pipeline.outbox.put_nowait({"type": "state", "data": "connected"})

            def on_event(_self, response):
                t = response.get("type", "")

                if t == "response.audio.delta":
                    delta = response.get("delta", "")
                    if delta:
                        pipeline.outbox.put_nowait({
                            "type": "audio",
                            "data": delta,
                            "format": "pcm24k",
                        })

                elif t in ("response.audio_transcript.delta", "response.text.delta"):
                    td = response.get("delta", "")
                    if td:
                        pipeline._text_buffer += td
                        pipeline.outbox.put_nowait({
                            "type": "text_chunk",
                            "data": td,
                        })

                elif t in ("response.audio_transcript.done", "response.text.done"):
                    final = response.get("transcript", "") or response.get("text", "") or pipeline._text_buffer
                    if final:
                        pipeline.outbox.put_nowait({
                            "type": "text_done",
                            "data": final,
                        })
                    pipeline._text_buffer = ""

                elif t == "conversation.item.input_audio_transcription.completed":
                    transcript = response.get("transcript", "")
                    if transcript:
                        pipeline.outbox.put_nowait({
                            "type": "speech_text",
                            "data": transcript,
                        })

                elif t == "response.function_call_arguments.done":
                    # 模型调用了工具 → 执行并返回结果
                    call_id = response.get("call_id", "")
                    func_name = response.get("name", "")
                    try:
                        args = json.loads(response.get("arguments", "{}"))
                    except json.JSONDecodeError:
                        args = {}
                    result = TOOL_MAP.get(func_name)
                    output = result.content if result else f"Unknown tool: {func_name}"
                    # 发送 function_call_output
                    pipeline.conv.send_raw(json.dumps({
                        "type": "conversation.item.create",
                        "item": {
                            "type": "function_call_output",
                            "call_id": call_id,
                            "output": output,
                        },
                    }))
                    # 触发新响应（工具调用后继续回复）
                    pipeline.conv.send_raw(json.dumps({"type": "response.create", "response": {}}))

                elif t == "response.done":
                    pipeline.outbox.put_nowait({"type": "audio_done"})

                elif "error" in t:
                    err = response.get("error", {}).get("message", str(response))
                    pipeline.outbox.put_nowait({"type": "error", "data": err})

                elif t == "input_audio_buffer.speech_started":
                    pipeline.outbox.put_nowait({"type": "state", "data": "listening"})

                elif t == "input_audio_buffer.speech_stopped":
                    pipeline.outbox.put_nowait({"type": "state", "data": "llm"})

            def on_close(_self):
                pipeline.outbox.put_nowait({"type": "state", "data": "disconnected"})

        return _CB()

    def connect(self):
        self.conv = OmniRealtimeConversation(
            model=OMNI_MODEL,
            callback=self._callback(),
        )
        self.conv.connect()
        # 等 session 建立
        time.sleep(0.3)
        self.conv.update_session(
            voice=OMNI_VOICE,
            output_modalities=[MultiModality.TEXT, MultiModality.AUDIO],
            instructions=OMNI_INSTRUCTIONS,
            input_audio_format=AudioFormat.PCM_16000HZ_MONO_16BIT,
            output_audio_format=AudioFormat.PCM_24000HZ_MONO_16BIT,
            enable_turn_detection=True,
            turn_detection_type="server_vad",
            turn_detection_threshold=0.2,
            turn_detection_silence_duration_ms=800,
        )
        # 注册 function calling 工具
        time.sleep(0.2)
        self.conv.send_raw(json.dumps({
            "type": "session.update",
            "session": {"tools": OMNI_TOOLS},
        }))

    def send_text(self, text: str):
        if not self.conv:
            return
        self.conv.create_item({
            "type": "message",
            "role": "user",
            "content": [{"type": "input_text", "text": text}],
        })
        # 使用 send_raw 避免 SDK 的 create_response 将 instructions=None 序列化为 null 覆盖 session 指令
        self.conv.send_raw(json.dumps({
            "type": "response.create",
            "response": {},
        }))

    def send_audio(self, b64: str):
        """发送 base64 PCM 16kHz 音频到 Omni。"""
        if not self.conv:
            return
        self.conv.append_audio(b64)

    def commit_audio(self):
        """通知 Omni 音频输入结束。"""
        if self.conv:
            self.conv.commit()
            self.conv.send_raw(json.dumps({"type": "response.create", "response": {}}))

    def cancel(self):
        if self.conv:
            self.conv.cancel_response()

    def close(self):
        if self.conv:
            try:
                self.conv.close()
            except Exception:
                pass
            self.conv = None


class OmniSessionManager:
    """管理多会话的 OmniPipeline。"""

    def __init__(self):
        self._pipelines: dict[str, OmniPipeline] = {}

    def get_or_create(self, session_id: str, outbox: asyncio.Queue) -> OmniPipeline:
        # 总是新建 pipeline，关掉旧的（刷新页面复用 session_id 时需要）
        old = self._pipelines.pop(session_id, None)
        if old:
            old.close()
        pipe = OmniPipeline(outbox)
        pipe.connect()
        self._pipelines[session_id] = pipe
        return pipe

    def remove(self, session_id: str):
        pipe = self._pipelines.pop(session_id, None)
        if pipe:
            pipe.close()


omni_manager = OmniSessionManager()
