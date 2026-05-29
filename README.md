# Fay Digital Human — 行业知识库智能助手

[English](README_EN.md)

基于多模态大模型的 3D 数字人实时对话系统。支持文本/语音双模式交互，集成 RAG 知识库检索与业务工具调用。

> **注意**：项目中的演示数据（值班表、事件列表、RAG 知识库文档）均为虚构示例。

![Main Interface](docs/screenshots/main-interface.png)
![Chat Response](docs/screenshots/chat-response.png)
![Voice Mode](docs/screenshots/voice-mode.png)

## 核心能力

- **实时语音对话** — 多模态模型 Qwen3-Omni 端到端处理音频输入/输出，无需串联 ASR/TTS
- **3D 数字人** — VRM 模型实时渲染，音频驱动嘴部动画，状态机控制表情与手势
- **RAG 知识检索** — 行业知识库向量检索，结合对话历史提供上下文精准回答
- **业务工具调用** — LLM 自动调用值班表查询、事件数据面板等 function calling
- **双模式运行** — WebSocket 实时语音（推荐）/ 文本 SSE 流式对话，一键切换
- **多 LLM 后端** — 支持 DashScope（推荐）、LM Studio 本地部署、OpenAI 兼容接口

## 技术架构

```
frontend (Vue 3 + Three.js/VRM + Vite :5173)
    │
    ├── SSE text ──────────────────> Python/Java backend :8081
    └── WebSocket audio/text ──────> Python/Java backend :8081
                                       │
                                       ├── Qwen3-Omni (端到端音频理解+生成)
                                       ├── RAG (LangChain4j + VectorStore + Local Docs)
                                       └── Tools (值班表 / 事件数据)
```

## 前置条件

### 必须

| 依赖 | 说明 |
|------|------|
| **DashScope API Key** | 从 [阿里云 DashScope 控制台](https://dashscope.console.aliyun.com/) 获取，费用按量计费 |
| **多模态模型** | Omni 实时语音模式强制使用多模态模型，推荐 `qwen3-omni-flash-realtime` 或 `qwen3.5-omni-plus-realtime` |
| Node.js 18+ | 前端构建与运行 |

### 可选

| 依赖 | 说明 |
|------|------|
| Python 3.12+ | Python 后端（推荐，无需数据库） |
| JDK 17+ + MySQL | Java 后端（含 RAG + 对话持久化） |
| LM Studio | 本地 LLM 推理（文本模式可用，语音模式仍需 DashScope Omni） |
| RAG 知识库文件夹 | Java 后端使用，见下方配置说明 |

### 关于 Omni 多模态模型

实时语音对话使用多模态模型（Qwen3-Omni 系列），此类模型内置音频理解与合成能力，一次调用即完成音频→理解→音频的全过程。**不需要**额外部署 ASR 或 TTS 服务。

纯文本模式可使用普通 LLM（包括 LM Studio 本地模型），响应为纯文本。

| 模型 | 适用场景 | 延迟 |
|------|---------|------|
| `qwen3-omni-flash-realtime` | 实时语音对话（推荐） | 低 |
| `qwen3.5-omni-plus-realtime` | 实时语音对话（更强推理） | 中 |
| `qwen-plus` / LM Studio 本地模型 | 仅文本对话 | — |

## 快速开始

### Python 后端（推荐）

```bash
# 1. 克隆仓库
git clone <repo-url> && cd rag-tools-agent-avatar

# 2. 配置 API Key
#    复制环境变量模板，然后将 .env 中的 DASHSCOPE_API_KEY 替换为你的 DashScope API Key
#    从 https://dashscope.console.aliyun.com/ 获取
cp Fay/.env.example Fay/.env
# 编辑 Fay/.env，找到下面这一行：
#   DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx
# 把 sk-xxxxxxxxxxxxxxxx 替换成你自己的 DashScope API Key（保留 sk- 前缀）

# 3. 启动后端
cd Fay
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
SERVER_PORT=8081 python3 -m uvicorn main:app --host 0.0.0.0 --port 8081 --reload
# 注意：SERVER_PORT 必须为 8081，与前端 Vite 代理配置一致

# 4. 另开终端，启动前端
cd frontend
npm install
npm run dev

# 5. 浏览器访问
open http://localhost:5173
```

### Java 后端（含 RAG）

```bash
# 确保已安装 JDK 17+ 和 MySQL
# 初始化数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS demo"
mysql -u root -p demo < src/main/resources/schema.sql

# 配置 RAG 知识库文件夹路径
# 编辑 src/main/java/com/example/demo/config/AiMemoryConfig.java
# 将 FileSystemDocumentLoader.loadDocuments 路径修改为你的知识库位置

# 配置 DashScope API Key
export DASHSCOPE_API_KEY=sk-your-key-here

# 启动
mvn spring-boot:run
```

## RAG 知识库配置

知识库文档通过 `AiMemoryConfig.java` 中的 `FileSystemDocumentLoader` 加载。在项目目录下创建 `knowledge-base/` 文件夹放置文档，并修改配置指向该路径：

```java
List<Document> documents = FileSystemDocumentLoader.loadDocuments(
    "knowledge-base/"  // 修改为你的文档目录
);
```

支持的文档格式：`.txt`、`.md`、`.pdf`、`.docx`。启动时自动索引，无需额外配置。

## 项目结构

```
rag-tools-agent-avatar/
├── Fay/                          # Python 后端 (FastAPI)
│   ├── main.py                   # 入口：SSE + WebSocket 路由
│   ├── omni_pipeline.py          # Qwen3-Omni 实时语音 WebSocket 代理
│   ├── pipeline.py               # 文本管线（SSE fallback）
│   ├── llm_client.py             # LLM 客户端（DashScope / LM Studio）
│   ├── tts_client.py             # TTS 客户端（SSE 文本模式的语音输出，Omni 模式不需要）
│   ├── tool_system.py            # 业务工具（值班表 / 事件数据）
│   ├── session_manager.py        # 会话内存管理
│   ├── models.py                 # 数据模型
│   ├── config.py                 # 配置加载
│   └── .env.example              # 环境变量模板
├── src/                          # Java 后端 (Spring Boot WebFlux)
│   └── main/java/com/example/demo/
│       ├── omni/                 # Qwen-Omni 实时管线（Java 实现）
│       ├── config/AiMemoryConfig.java  # RAG 向量检索配置
│       ├── controller/           # API 控制器
│       └── service/              # 业务服务 + LangChain4j AiService
├── frontend/                     # Vue 3 前端
│   ├── src/
│   │   ├── App.vue               # 主聊天 UI + 业务面板
│   │   ├── components/
│   │   │   └── DigitalHuman.vue  # Three.js VRM 3D 数字人渲染
│   │   └── lib/
│   │       ├── faySocket.ts      # WebSocket 通信
│   │       ├── audioCapture.ts   # PCM 16kHz 音频采集
│   │       ├── audioPlayer.ts    # PCM 24kHz 流式播放
│   │       └── sseStream.ts      # SSE 流解析
│   └── public/
│       └── keito.vrm             # 3D 虚拟人模型（by keito, free）
├── knowledge-base/               # RAG 知识库文档（自建）
├── docs/screenshots/             # 界面截图
└── LICENSE                       # MIT License
```

## FAQ

**Q: 语音模式为什么需要 DashScope，不能用本地模型？**
Qwen3-Omni 是多模态模型（同时处理音频输入/输出），目前无开源等价物能在消费级硬件上运行。纯文本模式可使用 LM Studio 本地模型。

**Q: 如何切换 Java 和 Python 后端？**
二者 API 兼容。Vite 代理默认指向 `127.0.0.1:8081`，确保对应后端在该端口运行即可。Python 后端功能精简无需 MySQL；Java 后端提供 RAG 和对话持久化。

**Q: 演示数据怎么替换？**
值班表数据在 Python 的 `tool_system.py`、Java 的 `EmergencyDutyUiTools.java` 和前端 `App.vue` 中各有一份，可根据需要替换。

## License

MIT License - 详见 [LICENSE](LICENSE)

VRM 模型 `keito.vrm` 版权归原作者所有，使用需遵循其授权条款。
