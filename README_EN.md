# RAG-Tools-Agent-Avatar — Multimodal Digital Human Agent

[中文](README.md)

A 3D digital human real-time conversation system powered by multimodal LLMs. Supports text/voice dual-mode interaction with RAG knowledge retrieval and business tool calling.

> **Note**: All demo data (duty rosters, event lists, RAG knowledge base documents) are fictional examples.

![Main Interface](docs/screenshots/main-interface.png)
![Chat Response](docs/screenshots/chat-response.png)
![Voice Mode](docs/screenshots/voice-mode.png)

## Features

- **Real-time Voice Conversation** — Multimodal Qwen3-Omni handles audio input/output end-to-end, no separate ASR/TTS pipeline needed
- **3D Digital Human** — VRM model rendered in real-time, audio-driven lip sync, state-machine-controlled expressions and gestures
- **RAG Knowledge Retrieval** — Vector search over domain knowledge base with conversation history for context-aware answers
- **Business Tool Calling** — LLM function calling for duty roster lookup, event data panels, and more
- **Dual Mode** — WebSocket real-time voice (recommended) / SSE text streaming, one-click toggle
- **Multi LLM Backend** — DashScope (recommended), LM Studio local deployment, OpenAI-compatible API

## Architecture

```
frontend (Vue 3 + Three.js/VRM + Vite :5173)
    │
    ├── SSE text ──────────────────> Python/Java backend :8081
    └── WebSocket audio/text ──────> Python/Java backend :8081
                                       │
                                       ├── Qwen3-Omni (end-to-end audio understanding + generation)
                                       ├── RAG (LangChain4j + VectorStore + Local Docs)
                                       └── Tools (Duty Roster / Event Data)
```

## Prerequisites

### Required

| Dependency | Notes |
|------------|-------|
| **DashScope API Key** | Get one from [Alibaba Cloud DashScope Console](https://dashscope.console.aliyun.com/), pay-as-you-go |
| **Multimodal Model** | Voice mode requires a multimodal model. Recommended: `qwen3-omni-flash-realtime` or `qwen3.5-omni-plus-realtime` |
| Node.js 18+ | Frontend build and dev server |

### Optional

| Dependency | Notes |
|------------|-------|
| Python 3.12+ | Python backend (recommended, no database required) |
| JDK 17+ + MySQL | Java backend (includes RAG + chat persistence) |
| LM Studio | Local LLM inference (text mode only; voice mode still needs DashScope Omni) |
| RAG Knowledge Base | Required for Java backend, see configuration below |

### About Omni Multimodal Models

Real-time voice conversation uses multimodal models (Qwen3-Omni series) that have built-in audio understanding and synthesis — a single call handles audio → comprehension → audio end-to-end. **No separate ASR or TTS deployment required.**

Text-only mode works with standard LLMs (including LM Studio local models).

| Model | Use Case | Latency |
|-------|----------|---------|
| `qwen3-omni-flash-realtime` | Real-time voice (recommended) | Low |
| `qwen3.5-omni-plus-realtime` | Real-time voice (stronger reasoning) | Medium |
| `qwen-plus` / LM Studio local models | Text chat only | — |

## Quick Start

### Python Backend (Recommended)

```bash
# 1. Clone the repo
git clone <repo-url> && cd rag-tools-agent-avatar

# 2. Configure API Key
#    Copy the environment template, then replace DASHSCOPE_API_KEY with your own key
#    Get your key from https://dashscope.console.aliyun.com/
cp Fay/.env.example Fay/.env
# Edit Fay/.env and find this line:
#   DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx
# Replace sk-xxxxxxxxxxxxxxxx with your actual DashScope API key (keep the sk- prefix)

# 3. Start the backend
cd Fay
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
SERVER_PORT=8081 python3 -m uvicorn main:app --host 0.0.0.0 --port 8081 --reload
# Note: SERVER_PORT must be 8081 to match the Vite proxy configuration

# 4. In another terminal, start the frontend
cd frontend
npm install
npm run dev

# 5. Open in browser
open http://localhost:5173
```

### Java Backend (with RAG)

```bash
# Requires JDK 17+ and MySQL
# Initialize the database
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS demo"
mysql -u root -p demo < src/main/resources/schema.sql

# Configure RAG knowledge base path
# Edit src/main/java/com/example/demo/config/AiMemoryConfig.java
# Update FileSystemDocumentLoader.loadDocuments to point to your documents directory

# Set DashScope API Key
export DASHSCOPE_API_KEY=sk-your-key-here

# Start
mvn spring-boot:run
```

## RAG Knowledge Base Setup

Documents are loaded via `FileSystemDocumentLoader` in `AiMemoryConfig.java`. Create a `knowledge-base/` directory in the project root, place your documents there, and update the config:

```java
List<Document> documents = FileSystemDocumentLoader.loadDocuments(
    "knowledge-base/"  // Path to your document directory
);
```

Supported formats: `.txt`, `.md`, `.pdf`, `.docx`. Documents are indexed automatically on startup.

## Project Structure

```
rag-tools-agent-avatar/
├── Fay/                          # Python backend (FastAPI)
│   ├── main.py                   # Entry point: SSE + WebSocket routes
│   ├── omni_pipeline.py          # Qwen3-Omni real-time voice WebSocket proxy
│   ├── pipeline.py               # Text pipeline (SSE fallback)
│   ├── llm_client.py             # LLM client (DashScope / LM Studio)
│   ├── tts_client.py             # TTS client (SSE text mode speech output)
│   ├── tool_system.py            # Business tools (duty roster / event data)
│   ├── session_manager.py        # In-memory session management
│   ├── models.py                 # Data models
│   ├── config.py                 # Configuration loader
│   └── .env.example              # Environment variable template
├── src/                          # Java backend (Spring Boot WebFlux)
│   └── main/java/com/example/demo/
│       ├── omni/                 # Qwen-Omni real-time pipeline (Java impl)
│       ├── config/AiMemoryConfig.java  # RAG vector retrieval config
│       ├── controller/           # API controllers
│       └── service/              # Business services + LangChain4j AiService
├── frontend/                     # Vue 3 frontend
│   ├── src/
│   │   ├── App.vue               # Main chat UI + business panels
│   │   ├── components/
│   │   │   └── DigitalHuman.vue  # Three.js VRM 3D avatar rendering
│   │   └── lib/
│   │       ├── faySocket.ts      # WebSocket communication
│   │       ├── audioCapture.ts   # PCM 16kHz audio capture
│   │       ├── audioPlayer.ts    # PCM 24kHz streaming playback
│   │       └── sseStream.ts      # SSE stream parser
│   └── public/
│       └── keito.vrm             # 3D avatar model (by keito, free)
├── knowledge-base/               # RAG knowledge base (create your own)
├── docs/screenshots/             # UI screenshots
└── LICENSE                       # MIT License
```

## FAQ

**Q: Why does voice mode require DashScope? Can I use a local model?**
Qwen3-Omni is a multimodal model (handles audio input and output simultaneously). There is currently no open-source equivalent that runs on consumer hardware. Text-only mode can use LM Studio local models.

**Q: How do I switch between Java and Python backends?**
Both share the same API. The Vite proxy defaults to `127.0.0.1:8081` — just make sure the running backend is on that port. The Python backend is simpler (no MySQL required); the Java backend offers RAG and chat persistence.

**Q: How do I replace the demo data?**
Duty roster data lives in three places: `tool_system.py` (Python), `EmergencyDutyUiTools.java` (Java), and `App.vue` (frontend). Update them as needed.

## License

MIT License — see [LICENSE](LICENSE)

The VRM model `keito.vrm` is copyright of its original author. Use it according to its license terms.
