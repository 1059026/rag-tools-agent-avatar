from __future__ import annotations

import uuid
from enum import Enum
from typing import Any
from dataclasses import dataclass, field


class Role(str, Enum):
    USER = "user"
    ASSISTANT = "assistant"
    SYSTEM = "system"


@dataclass
class ChatMessage:
    role: Role
    content: str


@dataclass
class Session:
    id: str = field(default_factory=lambda: uuid.uuid4().hex)
    messages: list[ChatMessage] = field(default_factory=list)

    def add(self, role: Role, content: str):
        self.messages.append(ChatMessage(role=role, content=content))

    def history(self) -> list[dict[str, str]]:
        return [{"role": m.role.value, "content": m.content} for m in self.messages]

    def clear(self):
        self.messages.clear()


class WsInType(str, Enum):
    AUDIO = "audio"
    TEXT = "text"
    PING = "ping"


class WsOutType(str, Enum):
    AUDIO = "audio"
    TEXT_START = "text_start"
    TEXT_CHUNK = "text_chunk"
    TEXT_DONE = "text_done"
    ERROR = "error"
    PONG = "pong"
    STATE = "state"


@dataclass
class WsMessage:
    """WebSocket message from client."""
    type: WsInType
    data: str | None = None       # base64 audio or text
    format: str = "pcm16"          # audio format hint


@dataclass
class ToolResult:
    name: str
    content: str
    ui_marker: str | None = None   # e.g. "[UI:DUTY_LIST]"
