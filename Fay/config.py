import os
from dotenv import load_dotenv

load_dotenv()


class Config:
    DASHSCOPE_API_KEY: str = os.getenv("DASHSCOPE_API_KEY", "")

    LLM_PROVIDER: str = os.getenv("LLM_PROVIDER", "dashscope")
    LLM_MODEL: str = os.getenv("LLM_MODEL", "qwen-plus")
    LM_STUDIO_BASE_URL: str = os.getenv("LM_STUDIO_BASE_URL", "http://127.0.0.1:1234/v1")
    LM_STUDIO_MODEL: str = os.getenv("LM_STUDIO_MODEL", "qwen2.5-coder-14b-instruct-mlx")

    OMNI_MODEL: str = os.getenv("OMNI_MODEL", "qwen3-omni-flash-realtime")
    OMNI_VOICE: str = os.getenv("OMNI_VOICE", "Cherry")

    ASR_PROVIDER: str = os.getenv("ASR_PROVIDER", "dashscope")
    TTS_PROVIDER: str = os.getenv("TTS_PROVIDER", "dashscope")
    TTS_MODEL: str = os.getenv("TTS_MODEL", "sambert-zhixiao-v1")
    TTS_VOICE: str = os.getenv("TTS_VOICE", "zhixiao")

    SERVER_PORT: int = int(os.getenv("SERVER_PORT", "8081"))


config = Config()
