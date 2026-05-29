from __future__ import annotations

import asyncio
import io
import wave
import struct
from dashscope.audio.tts import SpeechSynthesizer

from config import config

async def synthesize_speech(text: str) -> bytes:
    if config.TTS_PROVIDER == "dashscope":
        return await _dashscope_tts(text)
    elif config.TTS_PROVIDER == "edge_tts":
        return await _edge_tts(text)
    else:
        raise RuntimeError(f"Unknown TTS provider: {config.TTS_PROVIDER}")


async def _dashscope_tts(text: str) -> bytes:
    def _call():
        result = SpeechSynthesizer.call(
            model=config.TTS_MODEL,
            text=text,
            voice=config.TTS_VOICE,
            format="wav",
            sample_rate=24000,
            api_key=config.DASHSCOPE_API_KEY,
        )
        audio = result.get_audio_data()
        if audio:
            return audio
        raise RuntimeError("DashScope TTS returned no audio data")
    return await asyncio.to_thread(_call)


async def _edge_tts(text: str) -> bytes:
    import edge_tts
    communicate = edge_tts.Communicate(text, "zh-CN-XiaoxiaoNeural")
    chunks = []
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            chunks.append(chunk["data"])
    return b"".join(chunks)


def fix_wav_header(wav_bytes: bytes) -> bytes:
    """修复 sambert TTS 输出的 WAV 头（nframes 字段可能无效），用实际数据大小重写。"""
    if len(wav_bytes) < 44:
        return wav_bytes
    # 检查 RIFF 头
    if wav_bytes[:4] != b"RIFF":
        return wav_bytes
    data_size = len(wav_bytes) - 44
    # 修复 RIFF chunk size (offset 4) 和 data chunk size (offset 40)
    fixed = bytearray(wav_bytes)
    riff_size = len(wav_bytes) - 8
    fixed[4:8] = riff_size.to_bytes(4, "little")
    fixed[40:44] = data_size.to_bytes(4, "little")
    return bytes(fixed)


def compute_jaw_amplitude(wav_bytes: bytes, num_samples: int = 50) -> list[float]:
    with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
        n_channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        raw = wf.readframes(wf.getnframes())
        bytes_per_sample = sample_width
        total_samples = len(raw) // bytes_per_sample

        if total_samples == 0 or sample_width != 2:
            return [0.0] * num_samples

        fmt = f"<{total_samples}h"
        samples = struct.unpack(fmt, raw)
        if n_channels > 1:
            samples = samples[::n_channels]

    chunk_size = max(1, len(samples) // num_samples)
    amps = []
    for i in range(num_samples):
        start = i * chunk_size
        end = start + chunk_size
        chunk = samples[start:end]
        if not chunk:
            amps.append(0.0)
            continue
        rms = (sum(s * s for s in chunk) / len(chunk)) ** 0.5
        normalized = min(1.0, rms / 6000.0)
        amps.append(round(normalized, 4))
    return amps
