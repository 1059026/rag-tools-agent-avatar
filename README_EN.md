# Digital Human Agent — Java Implementation

[中文](README.md) | [🤖 AI Skill](docs/AI-SETUP.md)

A 3D digital human real-time conversation system powered by Qwen3-Omni multimodal LLM. Java Spring Boot WebFlux connects directly to DashScope WebSocket API. Vue 3 frontend with @pixiv/three-vrm renders a VRM avatar. Supports text/voice dual-mode interaction with RAG knowledge retrieval and Function Calling.

> **Note**: All demo data (duty rosters, event lists, RAG knowledge base documents) are fictional examples.

![Main Interface](docs/screenshots/main-interface.png)
![Chat Response](docs/screenshots/chat-response.png)
![Voice Mode](docs/screenshots/voice-mode.png)

## Features

- **Real-time Voice Conversation** — Qwen3-Omni handles audio end-to-end. Java acts as a WebSocket relay. No separate ASR/TTS pipeline needed.
- **3D Digital Human** — Keito VRM model (@pixiv/three-vrm) with audio-driven lip sync and gesture state machine
- **RAG Knowledge Retrieval** — LangChain4j + ONNX embedding model, vector search embedded in conversation flow
- **Business Tool Calling** — Omni Function Calling for duty roster, event panels, and knowledge base search
- **Dual Mode** — WebSocket real-time voice / SSE text streaming, one-click toggle
- **Keyword-enforced Tooling** — Java-side intent detection injects tool-calling instructions to prevent model hallucination

## Architecture

```
Browser ─WS─→ Java (OmniWebSocketHandler) ─WS─→ DashScope Qwen3-Omni
              │                                  │
              ├─ ContentRetriever (RAG)          ├─ ASR (built-in)
              ├─ EmergencyDutyUiTools            ├─ LLM (built-in)
              ├─ EmergencyEventUiTools           └─ TTS (built-in)
              ├─ Keyword detection
              └─ PersistentChatMemoryStore (MySQL)
```

## Tech Stack

| Layer | Technology | Port |
|-------|-----------|------|
| Frontend | Vue 3 + Vite 6 + @pixiv/three-vrm v3 + Three.js 0.184 | 5173 |
| Backend | Spring Boot 3.2.5 (WebFlux) + MyBatis + LangChain4j | 8081 |
| Model | DashScope qwen3.5-omni-plus-realtime (WebSocket) | Cloud |
| Database | MySQL 8.0 (Docker/OrbStack) | 3306 |
| Embedding | BgeSmallEnV15QuantizedEmbeddingModel (ONNX, local) | In-process |
| Knowledge Base | knowledge-base (user-provided documents) | Local |

## Prerequisites

### Required

| Dependency | Notes |
|------------|-------|
| **DashScope API Key** | Get from [Alibaba Cloud DashScope Console](https://dashscope.console.aliyun.com/), pay-as-you-go |
| JDK 17+ | Compile and run |
| Node.js 18+ | Frontend dev server |

### Optional

| Dependency | Notes |
|------------|-------|
| MySQL 8.0 | Chat memory persistence (core features work without it) |
| Local Multimodal Model | Set up your own Omni-compatible model service for offline deployment |

## Quick Start

```bash
# 1. Clone
git clone <repo-url> && cd rag-tools-agent-avatar

# 2. Set API Key
#    Edit src/main/resources/application.yml
#    Set dashscope.api-key to your DashScope API key

# 3. Start backend
mvn spring-boot:run

# 4. In another terminal, start frontend
cd frontend
npm install
npm run dev

# 5. Open browser
open http://localhost:5173
```

> For chat persistence, start MySQL, create `demo` database, and run `src/main/resources/schema.sql`.

## RAG Setup

Place your own documents in the `knowledge-base/` directory. The path is configured in `application.yml`:

```yaml
knowledge:
  base:
    path: knowledge-base
```

Documents are loaded and vectorized on startup by `AiMemoryConfig.java`. Supported formats: `.txt`, `.md`, `.pdf`, `.docx`. The embedding model runs locally via ONNX — no external API needed.

## Project Structure

```
├── pom.xml                                          # Maven dependencies
├── knowledge-base/                                   # User-provided documents
├── src/main/resources/
│   ├── application.yml                              # All configuration
│   ├── schema.sql                                   # DB init scripts
│   └── mapper/                                       # MyBatis XML
├── src/main/java/com/example/demo/
│   ├── omni/
│   │   ├── DashScopeRealtimeClient.java             # DashScope WS client ★★★
│   │   └── OmniWebSocketHandler.java                # Browser↔Omni relay ★★★
│   ├── config/
│   │   ├── AiMemoryConfig.java                      # RAG embedding + retrieval
│   │   └── WebSocketConfig.java                     # WS route registration
│   ├── service/
│   │   ├── EmergencyDutyUiTools.java                # Duty roster tool ★
│   │   ├── EmergencyEventUiTools.java               # Event data tool ★
│   │   └── PersistentChatMemoryStore.java           # Chat memory persistence
│   └── controller/
│       └── AgentController.java                     # SSE text fallback
├── frontend/
│   ├── public/keito.vrm                              # VRM model (20MB)
│   └── src/
│       ├── App.vue                                   # Main UI + panels
│       ├── components/DigitalHuman.vue               # 3D avatar ★
│       └── lib/
│           ├── faySocket.ts                          # WebSocket client
│           ├── audioCapture.ts                       # Mic capture
│           └── audioPlayer.ts                        # Audio playback
└── docs/screenshots/
```

## FAQ

**Q: Why does voice mode need DashScope? Can I use a local model?**
Qwen3-Omni is a multimodal model that processes audio input and generates audio output in a single pass. There is currently no open-source equivalent that runs on consumer hardware.

**Q: Can this run fully offline?**
Qwen3-Omni is currently only available via the DashScope API. For offline deployment, you'll need to set up your own multimodal model service compatible with the Omni WebSocket protocol. Note that text-only LLM tools like LM Studio cannot handle voice multimodality and are not a drop-in replacement.

**Q: How do I replace the demo data?**
Duty roster and event data are in `EmergencyDutyUiTools.java` (return values) and `App.vue` (panel rendering). Knowledge base documents are in `knowledge-base/`.

## License

MIT License — see [LICENSE](LICENSE)

The VRM model `keito.vrm` is copyright of its original author. Use it according to its license terms.
