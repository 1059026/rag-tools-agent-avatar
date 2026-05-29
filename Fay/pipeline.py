from __future__ import annotations

import asyncio
import base64
import re
from typing import AsyncIterator

from models import Session, Role, WsOutType
from llm_client import chat_stream
from tts_client import synthesize_speech, fix_wav_header
from session_manager import SessionManager

# 句子分隔正则：中文标点 + 换行
SENTENCE_RE = re.compile(r'[。！？\n]')


def _split_sentences(text: str) -> list[str]:
    """按标点分割文本为句子列表。"""
    parts = SENTENCE_RE.split(text)
    return [p.strip() for p in parts if p.strip()]


async def _streaming_tts(
    session: Session,
    full_text_gen: AsyncIterator[str],
) -> AsyncIterator[dict]:
    """LLM 流式输出时同步进行分句 TTS。"""
    accumulated = ""
    sentence_buffer = ""
    tts_tasks: list[tuple[int, asyncio.Task[bytes]]] = []
    tts_index = 0

    async for chunk in full_text_gen:
        accumulated += chunk
        sentence_buffer += chunk
        yield {"type": "text_chunk", "data": chunk}

        # 检测完整句子
        parts = SENTENCE_RE.split(sentence_buffer)
        if len(parts) > 1:
            # 前面的部分都是完整句子
            for complete in parts[:-1]:
                s = complete.strip()
                if s and len(s) >= 2:  # 最少2字符才算有效句子
                    idx = tts_index
                    tts_index += 1
                    task = asyncio.create_task(synthesize_speech(s))
                    tts_tasks.append((idx, task))
            sentence_buffer = parts[-1]

    # 处理剩余文本
    remaining = sentence_buffer.strip()
    if remaining:
        idx = tts_index
        tts_index += 1
        task = asyncio.create_task(synthesize_speech(remaining))
        tts_tasks.append((idx, task))

    yield {"type": "text_done", "data": accumulated}
    session.add(Role.ASSISTANT, accumulated)

    # 按顺序发送 TTS 结果
    if not tts_tasks:
        yield {"type": "audio_done"}
        return

    yield {"type": "state", "data": "tts"}

    completed = 0
    pending = dict(tts_tasks)

    while pending:
        done, _ = await asyncio.wait(
            pending.values(),
            return_when=asyncio.FIRST_COMPLETED,
        )
        for task in done:
            # 找到对应的 index
            idx = None
            for i, t in list(pending.items()):
                if t is task:
                    idx = i
                    del pending[i]
                    break
            if idx is None:
                continue
            try:
                audio_wav = task.result()
                audio_wav = fix_wav_header(audio_wav)
                audio_b64 = base64.b64encode(audio_wav).decode()
                yield {
                    "type": "audio_chunk",
                    "data": audio_b64,
                    "index": idx,
                }
                completed += 1
            except Exception as e:
                yield {"type": "error", "data": f"TTS({idx}) 失败: {e}"}

    yield {"type": "audio_done"}


async def process_text_input(
    session_id: str, text: str, with_tts: bool = False
) -> AsyncIterator[dict]:
    """处理文本输入。with_tts=True 时分句流式 TTS。"""
    mgr = SessionManager.get()
    session = mgr.get_or_create(session_id)
    session.add(Role.USER, text)

    if with_tts:
        async for msg in _streaming_tts(session, chat_stream(session.history(), session_id)):
            yield msg
    else:
        full_text = ""
        async for chunk in chat_stream(session.history(), session_id):
            full_text += chunk
            yield {"type": "text_chunk", "data": chunk}
        session.add(Role.ASSISTANT, full_text)
        yield {"type": "text_done", "data": full_text}


async def process_audio_input(
    session_id: str, audio_bytes: bytes, audio_fmt: str = "pcm16"
) -> AsyncIterator[dict]:
    """处理语音输入：ASR → LLM → 分句流式 TTS。"""
    from asr_client import transcribe_audio

    mgr = SessionManager.get()
    session = mgr.get_or_create(session_id)

    yield {"type": WsOutType.STATE, "data": "asr"}
    user_text = await transcribe_audio(audio_bytes, audio_fmt)
    if not user_text.strip():
        yield {"type": WsOutType.ERROR, "data": "语音识别为空"}
        return

    yield {"type": "text_chunk", "data": f"[用户]: {user_text}"}
    session.add(Role.USER, user_text)

    yield {"type": WsOutType.STATE, "data": "llm"}
    async for msg in _streaming_tts(session, chat_stream(session.history(), session_id)):
        yield msg
