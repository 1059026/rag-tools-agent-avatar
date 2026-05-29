from __future__ import annotations

from models import Session, Role


class SessionManager:
    _instance: SessionManager | None = None

    def __init__(self):
        self._sessions: dict[str, Session] = {}

    @classmethod
    def get(cls) -> SessionManager:
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def get_or_create(self, session_id: str) -> Session:
        if session_id not in self._sessions:
            self._sessions[session_id] = Session(id=session_id)
        return self._sessions[session_id]

    def clear(self, session_id: str):
        if session_id in self._sessions:
            self._sessions[session_id].clear()

    def delete(self, session_id: str):
        self._sessions.pop(session_id, None)
