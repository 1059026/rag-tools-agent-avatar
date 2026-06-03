# 数字人 — Java 实现方案

> 编写日期：2026-05-19（更新：2026-06-03）
> 用途：主力应急项目迁移参考文档，供 AI Coding 辅助还原全部功能

---

## 一、项目概述

应急行业数字人交互系统。前端 Vue3 + @pixiv/three-vrm 渲染 3D VRM 数字人 (Keito)，后端 Java Spring Boot WebFlux 直连阿里云 DashScope Qwen3-Omni 多模态实时模型，实现文本/语音双模对话、RAG 知识库检索、Function Calling 工具调用（值班表/事件面板），全程无需中间 Python 层。

### 技术栈

| 层 | 技术 | 端口 |
|---|---|---|
| 前端 | Vue 3 + Vite 6 + @pixiv/three-vrm v3 + Three.js 0.184 | 5173 |
| 后端 | Spring Boot 3.2.5 (WebFlux) + MyBatis + LangChain4j + Java-WebSocket | 8081 |
| 模型 | DashScope qwen3.5-omni-plus-realtime (WebSocket 实时 API) | 云服务 |
| 数据库 | MySQL 8.0 (Docker/OrbStack) | 3306 |
| 嵌入模型 | BgeSmallEnV15QuantizedEmbeddingModel (本地 ONNX) | 进程内 |
| 知识库 | knowledge-base (20 份 Markdown，随项目分发) | 本地文件 |

### 核心架构

```
浏览器 ─WS─→ Java (OmniWebSocketHandler) ─WS─→ DashScope qwen3-omni
              │                                  │
              ├─ ContentRetriever (RAG)          ├─ 语音识别 (内置)
              ├─ EmergencyDutyUiTools            ├─ LLM 推理 (内置)
              ├─ EmergencyEventUiTools           └─ 语音合成 (内置)
              ├─ 关键词检测 (强制工具调用)
              └─ PersistentChatMemoryStore (MySQL)
```

Qwen3-Omni 是端到端多模态模型，内部已包含 ASR + LLM + TTS。Java 侧不额外集成 ASR/TTS 管道，只做 WebSocket 中继 + 业务逻辑（RAG / 工具 / 记忆）。

---

## 二、项目结构与文件清单

```
项目根目录/
├── pom.xml                                          # Maven 依赖
├── knowledge-base/                                   # 应急知识库 (20 .md)
├── src/main/resources/
│   ├── application.yml                              # 全部配置
│   ├── schema.sql                                   # 数据库初始化
│   └── mapper/
│       ├── UserMapper.xml
│       └── ChatMessageHistoryMapper.xml
├── src/main/java/com/example/demo/
│   ├── DemoApplication.java                         # 启动类
│   ├── config/
│   │   ├── AiMemoryConfig.java                      # RAG 嵌入+检索 Bean ★
│   │   ├── CorsWebFluxConfig.java                   # CORS 配置
│   │   └── WebSocketConfig.java                     # WebSocket 路由注册 ★
│   ├── controller/
│   │   ├── AgentController.java                     # /api/agent 端点 (SSE 文本后备)
│   │   └── UserController.java                      # /api/users CRUD
│   ├── model/
│   │   ├── User.java                                # 用户实体
│   │   └── ChatMessageHistory.java                  # 聊天记录实体
│   ├── mapper/
│   │   ├── UserMapper.java
│   │   └── ChatMessageHistoryMapper.java
│   ├── service/
│   │   ├── AgentService.java                        # SSE 文本对话服务 (后备)
│   │   ├── chatAIservice.java                       # LangChain4j @AiService 接口
│   │   ├── EmergencyDutyUiTools.java                # 值班表工具 ★
│   │   ├── EmergencyEventUiTools.java               # 事件数据工具 ★
│   │   ├── PersistentChatMemoryStore.java           # MySQL 会话记忆 ★
│   │   └── UserService.java
│   └── omni/
│       ├── DashScopeRealtimeClient.java             # DashScope WS 客户端 ★★★
│       └── OmniWebSocketHandler.java                # 浏览器 ↔ DashScope 中继 + 工具调度 ★★★
├── frontend/
│   ├── vite.config.ts                               # 代理 + Three.js dedupe
│   ├── package.json                                 # @pixiv/three-vrm + three@0.184 + vue
│   ├── index.html
│   ├── public/
│   │   └── keito.vrm                                # VRM 角色模型 (Booth.pm, Keito A, 20MB)
│   └── src/
│       ├── main.ts
│       ├── vite-env.d.ts                            # three-vrm 类型声明
│       ├── App.vue                                  # 主应用组件 (含音画同步)
│       ├── assets/main.css
│       ├── components/
│       │   └── DigitalHuman.vue                     # VRM 3D 数字人 (186行) ★
│       └── lib/
│           ├── faySocket.ts                         # WebSocket 客户端 ★
│           ├── audioCapture.ts                      # 麦克风采集 PCM16
│           ├── audioPlayer.ts                       # PCM24k 音频播放 + RMS 振幅
│           ├── speechRecognition.ts                 # 浏览器 ASR (备用, 未使用)
│           ├── sseStream.ts                         # SSE 流读取 (后备)
│           └── useRealtime.ts                       # OpenAI Realtime 参考 (未使用)
```

★ 标记为核心功能文件

---

## 三、核心功能实现

### 3.1 DashScope Omni 实时语音对话

**文件**: `omni/DashScopeRealtimeClient.java` + `omni/OmniWebSocketHandler.java`

**协议**: DashScope 兼容 OpenAI Realtime API 的 WebSocket JSON 协议。

**连接流程**:
1. 浏览器连接 `ws://host:8081/ws/chat?session_id=xxx`
2. `OmniWebSocketHandler.handle()` 创建 `DashScopeRealtimeClient`
3. `client.connect()` 连接 `wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=xxx`
4. `client.initializeSession()` 在独立线程发送 `session.update` 配置：
   - 模态: text + audio
   - 输入音频: PCM 16kHz mono 16bit
   - 输出音频: PCM 24kHz mono 16bit
   - VAD: server_vad, threshold=0.2, silence=800ms
   - 注册 3 个 function calling 工具
5. 双向中继：浏览器消息 → DashScope，DashScope 事件 → 浏览器

**消息格式（浏览器 ↔ 服务端）**:
```json
// 浏览器 → 服务端
{"type":"text","data":"你好"}
{"type":"audio","data":"<base64 pcm16>","format":"pcm16"}
{"type":"cancel"}

// 服务端 → 浏览器 (新增 show_panel)
{"type":"text_chunk","data":"你"}
{"type":"text_done","data":"你好，我是..."}
{"type":"audio","data":"<base64 pcm24k>","format":"pcm24k"}
{"type":"audio_done"}
{"type":"speech_text","data":"用户说的话"}
{"type":"state","data":"connected|listening|llm"}
{"type":"show_panel","data":"duty|event"}
{"type":"error","data":"..."}
```

**空转超时重连**（关键实现）:
- DashScope 在 300 秒无输入后发送 `user_idle_timeout` 错误并关闭连接
- 服务端收到 `onClose` 后**不关闭**浏览器 WebSocket，只标记 client 断开
- 下一条用户消息到达时，检测到 client 未连接，自动执行重连流程
- 重连时：创建新 client → connect → initializeSession → 发送缓冲消息
- `"disconnected"` 状态不转发给前端（避免前端误认为会话结束）

### 3.2 Function Calling 工具调用

**工具定义**（`OmniWebSocketHandler` 中）:
```java
// 工具1: 值班表
{"type":"function","name":"showEmergencyDutyList",
 "description":"显示当前应急值班表。当用户询问谁在值班、值班表、排班时调用。"}

// 工具2: 事件数据
{"type":"function","name":"showEmergencyEventData",
 "description":"显示应急事件数据面板。当用户询问事件列表、警情、险情时调用。"}

// 工具3: 知识库搜索 (带参数)
{"type":"function","name":"search_knowledge_base",
 "description":"搜索应急行业知识库获取专业知识。",
 "parameters":{"query":{"type":"string","description":"搜索关键词"}}}
```

**执行流程**:
1. DashScope 发来 `response.function_call_arguments.done` 事件
2. `handleFunctionCall()` 根据 tool name 分发：
   - `showEmergencyDutyList` → `EmergencyDutyUiTools.showDutyList()` → 返回 `[UI:EMERGENCY_DUTY_LIST]`
   - `showEmergencyEventData` → `EmergencyEventUiTools.showEventData()` → 返回 `[UI:EMERGENCY_EVENT_DATA]`
   - `search_knowledge_base` → `ContentRetriever.retrieve(Query.from(args.query))` → 返回检索到的文档文本
3. 结果通过 `sendFunctionCallOutput(callId, output)` 发回 DashScope
4. **同时**向浏览器推送 `show_panel` 消息，前端直接打开面板（不依赖模型文本输出中的标记）
5. DashScope 基于工具输出生成最终回复

**关键词强制工具调用**（关键实现）:

Qwen3-Omni 的 Function Calling 存在不稳定性——有时模型会编造回答而不调用工具。为此在 `dispatch()` 中加入关键词前置检测：

```java
// 关键词 → 强制工具映射
String[][] FORCE_TOOL_PATTERNS = {
    {"值班", "排班", "谁在岗", "谁在上班", "今天谁", "明天谁", "这周谁", "值班表"},
    {"事件", "警情", "险情", "灾情", "事故", "突发"},
    {"知识", "规范", "预案", "标准", "怎么处置", "如何应对", "怎么办", "应急", "安全"}
};

// 匹配到关键词时，在用户消息前注入系统指令：
// "【系统指令：你必须先调用xxx工具，再把结果告诉用户。不要自己编造信息。】"
```

**前端 UI 面板触发（双路径）**:
1. **WebSocket `show_panel` 消息**（主要方式）：Java 侧工具执行时直接推送，前端 `onShowPanel` 回调设置面板 ref = true，不依赖文本解析
2. **文本标记解析**（后备）：前端 `App.vue` 实时解析流式文本中的 `[UI:EMERGENCY_DUTY_LIST]` 和 `[UI:EMERGENCY_EVENT_DATA]` 标记

面板数据硬编码在前端（3 条值班记录 + 3 条事件记录），通过 `<Teleport to="body">` 渲染为模态弹窗，支持 Escape 关闭。

### 3.3 RAG 知识库检索

**知识库加载**（`AiMemoryConfig.java`）:
```java
// 1. 加载项目内知识库文档（路径通过 application.yml 配置）
@Value("${knowledge.base.path:knowledge-base}")
private String knowledgeBasePath;
List<Document> documents = FileSystemDocumentLoader.loadDocuments(knowledgeBasePath);

// 2. 嵌入模型（本地 ONNX，无需外部 API）
EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();

// 3. 构建向量数据库（内存）
InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();

// 4. 文档分段 + 向量化 + 存储
EmbeddingStoreIngestor.builder()
    .embeddingModel(embeddingModel)
    .embeddingStore(store)
    .build()
    .ingest(documents);

// 5. 检索器（最低相似度 0.5，最多 3 个结果）
ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(store)
    .minScore(0.5)
    .maxResults(3)
    .build();
```

**检索调用**（`OmniWebSocketHandler.retrieveContext()`）:
```java
List<Content> contents = contentRetriever.retrieve(Query.from(userText));
// 格式化为：【知识库参考资料】--- {文档内容} ---
// 通过 search_knowledge_base 工具返回给模型
```

**注意**: 知识库文档为中文，但嵌入模型 `BgeSmallEnV15` 为英文模型。实测检索效果可接受。迁移后如需更好精度，可替换为 `BgeSmallZh` 中文嵌入模型或使用 DashScope Embedding API。

### 3.4 会话记忆持久化

**文件**: `PersistentChatMemoryStore.java`

**机制**:
- 实现 LangChain4j 的 `ChatMemoryStore` 接口
- 通过 MyBatis 读写 MySQL `chat_message_history` 表
- 每条消息序列化为 LangChain4j JSON 格式存入 `content` 列
- 角色映射: USER/AI/SYSTEM → 同名, 其他 → OTHER
- 窗口大小: 10 条消息（`MessageWindowChatMemory`）

**表结构**（需手动创建，schema.sql 未包含）:
```sql
CREATE TABLE chat_message_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_id VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**注意**: 此会话记忆仅在 LangChain4j SSE 文本路径使用。Omni 语音路径的对话状态由 DashScope 服务端维护。

### 3.5 3D 数字人渲染 (VRM)

**文件**: `components/DigitalHuman.vue` (186行)

**模型**: Keito A (けいとA), VRoid Studio 制作, Booth.pm 购入。VRM 1.0 格式, glTF 2.0 binary, 20MB, 67骨骼, 80+ blendshapes。

**技术栈**: `@pixiv/three-vrm` v3 + Three.js 0.184 + GLTFLoader。VRM 通过 VRMLoaderPlugin 加载, `autoUpdateHumanBones: false` 防止骨骼覆盖。

**渲染管线**:
- WebGLRenderer: alpha:true, ACESFilmicToneMapping, PCFSoftShadowMap
- 相机: PerspectiveCamera(40°), 位于 (0,1.05,3.2)
- VRM 包裹在 Group 中旋转 180° 面对镜头
- 光照: AmbientLight + 3×DirectionalLight (主光/补光/轮廓光) + 阴影平面

**自然站立姿态**: 硬编码在 `NATURAL` 常量中:
```
LeftArm [0.06,0.61,1.36]   RightArm [0.06,0.11,-1.39]
LeftForearm [0,0,0]        RightForearm [0,0,0]
LeftHand [-0.54,-0.19,0.31] RightHand [0.31,-0.24,-0.24]
```

**唇形同步**: 使用 VRM ExpressionManager 预设, 跟随 `jawAmplitude` 实时驱动 `Aa` (张口) + `Happy` (微笑)。公式: `aa = 0.03 + amp*0.35`, `happy = amp*0.15`。

**说话手势**: 5 个预定义手势姿势, 基于自然姿态的偏移值。说话时随机交替, 0.5s ease-in-out 过渡, 保持 2-5s, 单侧手动作(左右不组合)。手势数据:
```
右手1: rArm[-0.62,-0.51,-1.28] rFore[0.10,1.15,-0.04] rHand[0.81,-0.01,-0.36]
右手2: rArm[-0.13,0.33,-1.28]  rFore[0.57,0.50,0.26]  rHand[1.11,0.00,-0.81]
左手1: lArm[1.10,0.05,1.01]    lFore[0.59,-0.58,1.13] lHand[-0.08,0.03,0.10]
左手2: lArm[0.72,0.50,1.27]    lFore[1.01,-0.06,0.33] lHand[-0.54,-0.19,0.31]
```

**待机动画**: 身体摇摆(group rotation ±0.04), 转头(head bone ±0.12), 呼吸(spine scale ±0.012)。

**调试面板**: 18 滑块 × (L/R UpperArm/Forearm/Hand × 3轴), 默认隐藏 (`dShow=false`)。用于后续调试新姿势。

**音画同步**: 前端 `App.vue` 中 Omni 返回的文字暂存不显示，等首个音频 chunk 到达后同步释放，避免文字跑在声音前面的割裂感。

**关键踩坑**:
1. VRoid 模型 A-pose 手臂平展, 需手动旋转骨骼至自然下垂位置
2. 标准化骨骼 (VRMHumanoid) 旋转无法驱动蒙皮, 必须直接操作底层 J_Bip 骨骼
3. `autoUpdateHumanBones: true` (默认) 会在 `vrm.update()` 时同步底层骨骼→标准化骨骼, 导致标准化骨骼赋值被覆盖。须设为 `false`
4. 左右臂使用不同旋转轴: 左臂 Z 轴为主, 右臂 X 轴为主 (VRM 骨骼坐标规范)
5. 前臂弯曲方向: 正值向前弯曲, 负值向后折断(非自然)
6. VRM 默认面向 +Z, 相机在 +Z 看原点, 需 Group 旋转 180° 让模型面对镜头

### 3.6 音频采集与播放

**采集** (`audioCapture.ts`):
- `getUserMedia({ sampleRate: 16000, channelCount: 1 })`
- ScriptProcessorNode buffer=2048，Float32→Int16 PCM
- 每 100ms 打包为 base64 发送

**播放** (`audioPlayer.ts`):
- 解码 base64 PCM 24kHz → Float32 → AudioBuffer
- 队列顺序播放，自动衔接
- 计算 RMS 振幅 → 驱动口型

---

## 四、配置说明

### application.yml 关键配置

```yaml
server:
  port: 8081

knowledge:
  base:
    path: knowledge-base         # 知识库文档目录 (相对于项目根目录)

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/demo
    username: root
    password: "123456"

# DashScope Omni 配置
dashscope:
  api-key: sk-xxx                 # 阿里云 DashScope API Key
  omni:
    model: qwen3.5-omni-plus-realtime
    voice: Tina
    instructions: "你是应急行业专业助手。你拥有三个工具：showEmergencyDutyList（查看值班表）、showEmergencyEventData（查看事件数据）、search_knowledge_base（搜索知识库）。核心规则：只要用户的意图涉及查看、了解、查询上述三类信息中的任何一种，你就必须先调用对应工具。不确定时宁可调用工具，不要猜测。【重要】工具返回结果中的 [UI:xxx] 标记是给界面用的指令，绝对不要在语音中朗读这些标记。"

# LangChain4j (SSE 文本路径，后备)
langchain4j:
  open-ai:
    chat-model:
      base-url: http://127.0.0.1:1234/v1
      api-key: lm-studio
      model-name: qwen2.5-coder-14b-instruct-mlx
    streaming-chat-model:
      base-url: http://127.0.0.1:1234/v1
      api-key: lm-studio
      model-name: qwen2.5-coder-14b-instruct-mlx
```

### vite.config.ts 代理配置

```typescript
export default defineConfig({
  plugins: [vue()],
  resolve: {
    dedupe: ['three'],               // 防止 @pixiv/three-vrm 引入第二份 Three.js
  },
  server: {
    port: 5173,
    proxy: {
      "/api/agent": { target: "http://127.0.0.1:8081", changeOrigin: true },
      "/api":       { target: "http://127.0.0.1:8081", changeOrigin: true },
      "/ws":        { target: "ws://127.0.0.1:8081", ws: true, changeOrigin: true },
    },
  },
});
```

### pom.xml 关键依赖

```xml
<parent>spring-boot-starter-parent 3.2.5</parent>
<java.version>17</java.version>

<!-- WebFlux（响应式 WebSocket） -->
<dependency>spring-boot-starter-webflux</dependency>

<!-- MyBatis + MySQL -->
<dependency>mybatis-spring-boot-starter 3.0.3</dependency>
<dependency>mysql-connector-j</dependency>

<!-- WebSocket 客户端（连接 DashScope） -->
<dependency>org.java-websocket:Java-WebSocket:1.5.7</dependency>

<!-- LangChain4j -->
<dependency>langchain4j-open-ai-spring-boot-starter:1.0.0-beta3</dependency>
<dependency>langchain4j-spring-boot-starter:1.0.0-beta3</dependency>
<dependency>langchain4j-reactor:1.0.0-beta3</dependency>
<dependency>langchain4j-easy-rag:1.0.0-beta3</dependency>
```

### 前端依赖 (package.json)

```json
{
  "three": "^0.184.0",
  "@pixiv/three-vrm": "^3.5.3",
  "vue": "^3.5.13"
}
```

---

## 五、启动步骤

1. **MySQL**: 确保 Docker/OrbStack 中 MySQL 运行在 3306，数据库 `demo` 已创建
2. **API Key**: 在 `application.yml` 中配置 `dashscope.api-key`
3. **启动 Java**:
   ```bash
   cd 项目目录
   mvn spring-boot:run
   ```
4. **启动前端**（务必从 frontend 目录启动，确保读取本地 vite.config.ts）:
   ```bash
   cd frontend
   npx vite --port 5173
   ```
5. **访问**: `http://localhost:5173`

---

## 六、踩坑记录（重要）

### 坑1: java.net.http.WebSocket 与 DashScope 不兼容

**现象**: Java 内置的 `java.net.http.WebSocket` 连接 DashScope 后可以成功发送消息，但**完全收不到任何回复**（连 `session.created` 都不触发）。

**根因**: Java 内置 WebSocket 实现与 DashScope 服务端存在兼容性问题，`onText` 回调永不触发。

**解决**: 替换为 `org.java-websocket:Java-WebSocket:1.5.7` 第三方库。该库与 Python 的 `websocket-client` 行为一致，可以正常收发。

**代码位置**: `DashScopeRealtimeClient.java` 内部类 `WsClient extends WebSocketClient`

---

### 坑2: WebSocket 回调线程内发送消息导致连接断开

**现象**: 在 `onOpen` 回调链中调用 `ws.send()` 发送 `session.update`，间歇性导致 DashScope 立即关闭连接（connected → disconnected）。有时能成功，有时失败，非常不稳定。

**根因**: `org.java-websocket` 的 `onOpen` 回调在 WebSocket 内部线程执行。从该线程调用 `send()` 可能与库的内部状态机冲突。

**解决**: 
1. `connect()` 方法只建立连接，返回 CompletableFuture（在 `onOpen` 时完成）
2. `initializeSession()` 方法在**独立的新线程**中执行所有 `session.update` 发送
3. 调用方: `client.connect().thenAccept(v -> client.initializeSession())`
4. 独立线程中先用 `Thread.sleep(300)` 等待 `session.created` 到达，再发送配置

**代码位置**: 
- `DashScopeRealtimeClient.connect()` 
- `DashScopeRealtimeClient.initializeSession()` — 在新线程 `"dashscope-init"` 中运行

---

### 坑3: Map.of() 不接受 null 值

**现象**: `NullPointerException` 在 session 初始化时抛出，但没有堆栈跟踪（被 CompletableFuture 吞掉）。

**根因**: `Map.of("model", null)` — Java 9+ 的 `Map.of()` 明确禁止 null 键和值。

**解决**: 使用 `new HashMap<>()` 或 `new LinkedHashMap<>()` 代替 `Map.of()` 来构建包含 null 值的 map。

**代码位置**: `DashScopeRealtimeClient.initializeSession()` 中 session params 构建

---

### 坑4: tools 不能合并到主 session.update

**现象**: 将 tools 数组合并到 session config 的同一个 `session.update` 消息中发送，DashScope 连接后立即断开。

**解决**: 分两次发送 `session.update`：
1. 第一条: 包含 modalities、voice、VAD、音频格式（不含 tools）
2. 间隔 200ms
3. 第二条: 只包含 `{"session": {"tools": [...]}}`

**代码位置**: `DashScopeRealtimeClient.initializeSession()`

---

### 坑5: qwen3.5-omni-plus-realtime 不支持 Cherry 语音

**现象**: `{"error":{"code":"COMMON_ERROR","message":"Voice 'Cherry' is not supported."}}`

**解决**: 使用 `Tina` 语音。

---

### 坑6: 300 秒空闲超时 + 重连 Bug

**现象**: 用户一段时间不操作后，DashScope 发送 `user_idle_timeout` 错误并关闭 WebSocket。前端收到错误后无法继续对话。首次实现重连后仍然不工作。

**第一层问题（超时）**：DashScope 300 秒无输入断开连接。

**初版解决方案**:
1. 滤掉 `"disconnected"` 状态消息，不转发给前端
2. 浏览器 WS 连接保持打开
3. 下一条用户消息到达时，检测 `client.isOpen()` 为 false → 触发重连
4. 重连: 创建新 `DashScopeRealtimeClient` → connect → initializeSession → 发送缓冲消息

**第二层 bug（重连不生效）**: 重连判断逻辑写在了 `if (!ready.get())` 的 else 分支中。但空闲超时后 `ready` 标志位仍为 `true`（该标志只在初始连接时设为 true，从不会被重置为 false），导致消息走 `dispatch()` → `sendJson()` 检测 ws 已断开 → 静默丢弃。用户看到的就是"发了消息毫无反应"。

**最终修复**: 将死连接检测提到 `ready` 检查之前，独立判断：

```java
// 先检测死连接（无论 ready 状态如何）
if (!clientHolder[0].isOpen() && ready.get()) {
    ready.set(false);           // 重置标志
    pendingMessages.add(raw);   // 缓冲当前消息
    reconnect.run();            // 异步重连
    return;
}
// 正常路径
if (ready.get()) {
    dispatch(clientHolder[0], raw, finalSid);
} else {
    pendingMessages.add(raw);
}
```

**代码位置**: `OmniWebSocketHandler.handle()` 中的 `session.receive()` 订阅 + `reconnect` Runnable

---

### 坑7: Vite 版本不一致导致代理不生效

**现象**: 前端启动后 WebSocket 代理不通（能连上但无响应），而直接连 Java 8081 正常。

**根因**: 在非 frontend 目录下执行 `npx vite` 使用了全局缓存的 Vite 版本，且不读取项目目录的 `vite.config.ts`。

**解决**: 务必从 `frontend/` 目录启动：`cd frontend && npx vite --port 5173`，确保使用本地 node_modules 中的 Vite。

---

### 坑8: 浏览器消息在连接建立前到达导致丢失

**现象**: 浏览器在 `client.connect()` 完成前发送的消息没有被处理。

**解决**: 使用 `ConcurrentLinkedQueue` 缓冲尚未发送的消息。连接就绪后（`ready.set(true)`），从队列中取出并重放。

**代码位置**: `OmniWebSocketHandler.handle()` 中的 `pendingMessages` 队列

---

### 坑9: ContentRetriever 检索中文文档效果

**注意点**: 当前嵌入模型 `BgeSmallEnV15QuantizedEmbeddingModel` 是英文模型。实测可以正常检索中文文档。迁移到新项目时如检索效果不佳，建议替换为中文嵌入模型（如 `BgeSmallZh` 或 DashScope Text Embedding API）。

---

### 坑10: chat_message_history 表需手动创建

`schema.sql` 只包含 `user` 表的 DDL。`chat_message_history` 表虽然在 MyBatis mapper XML 中有引用，但 DDL 未包含。需要手动创建或由应用自动建表。

---

### 坑11: VRM 骨骼旋转必须直接操作 J_Bip 原始骨骼

**现象**: 使用 `VRMHumanoid.getNormalizedBoneNode()` 设置手臂旋转后，模型外观不变。

**根因**: `autoUpdateHumanBones: true` (默认) 在 `vrm.update()` 时同步底层骨骼→标准化骨骼，覆盖手动设定的值。标准化骨骼是"只读"视图。

**解决**: 
1. 设置 `VRMLoaderPlugin(parser, { autoUpdateHumanBones: false })`
2. 直接操作底层原始骨骼: `vrm.scene.getObjectByName('J_Bip_L_UpperArm')`
3. 左右臂使用不同旋转轴: 左臂 Z 轴, 右臂 X 轴 (VRM 骨骼坐标规范)

**代码位置**: `DigitalHuman.vue` — `loadModel()` 和 `animate()`

---

### 坑12: VRoid 模型 A-pose 手臂平展

**现象**: VRoid Studio 导出的 VRM 默认 A-pose (手臂水平展开 45°), 直接加载看起来像"耶稣姿势"。

**解决**: 加载后手动旋转上臂骨骼至自然下垂位置。使用 18 轴调试面板逐轴调节, 最终硬编码为 NATURAL 常量。

**代码位置**: `DigitalHuman.vue` — `NATURAL` 常量 + debug-panel

---

### 坑13: @pixiv/three-vrm 与项目 Three.js 版本冲突

**现象**: `@pixiv/three-vrm` 依赖 `three@^0.180.0`, 项目使用 `three@0.184.0`, npm 安装两份 Three.js 导致 `instanceof THREE.Object3D` 检查失败, 渲染异常。

**解决**: Vite 配置 `resolve.dedupe: ['three']` 强制统一为项目版本。

**代码位置**: `vite.config.ts`

---

### 坑14: GLTFLoader 无法加载 .vrm 扩展名

**现象**: 直接用 GLTFLoader 加载 .vrm 报 "Armature not found"。

**根因**: VRM 使用不同的骨骼命名规范 (J_Bip_* 而非 mixamorig), 且根骨骼名为 "Root" 而非 "Armature"。

**解决**: 使用 @pixiv/three-vrm 的 VRMLoaderPlugin 加载, 不通过 TalkingHead。

---

### 坑15: Qwen3-Omni Function Calling 不稳定——模型编造回答不调工具

**现象**: 用户问"今天谁值班"，模型直接编了一段值班信息而不调用 `showEmergencyDutyList` 工具。

**根因**: Qwen3-Omni 的 Function Calling 对中文口语化表达（"这两天的排班情况我看看"）的意图判断不够可靠，有时优先用语言能力直接回答。

**解决（双保险）**:
1. Prompt 优化：告诉模型"不确定时宁可调用工具，不要猜测"
2. **关键词前置检测**：Java 侧 `dispatch()` 在用户消息发给 Omni 前检测关键词，匹配到则注入强制指令前缀 `【系统指令：你必须先调用xxx工具，再把结果告诉用户。不要自己编造信息。】`
3. **面板推送**：工具执行时直接发 `show_panel` WebSocket 消息给前端，不依赖模型在回复文本中保留 UI 标记

**代码位置**: `OmniWebSocketHandler.dispatch()` + `OmniWebSocketHandler.handleFunctionCall()`

---

## 七、迁移清单

将本方案迁移到应急主力项目时，需要处理以下事项：

| 事项 | 说明 |
|------|------|
| API Key | `dashscope.api-key` 配置在 application.yml |
| 数据库 | MySQL 连接信息、chat_message_history 表 |
| 知识库路径 | `knowledge.base.path` 配置，默认 `knowledge-base` |
| 知识库文档 | 复制 `knowledge-base/` 目录（20 篇 .md） |
| 嵌入模型 | 当前 BgeSmallEnV15（英文），建议换 BgeSmallZh 中文模型 |
| 前端模型 | `public/keito.vrm` (20MB) 需复制 |
| 前端依赖 | `npm install three @pixiv/three-vrm vue` |
| 端口规划 | 避免 8081/5173 端口冲突 |
| DashScope 模型 | `qwen3.5-omni-plus-realtime`（注意时效性） |
| CORS | `CorsWebFluxConfig.java` 调整为生产环境域名 |
| WebSocket URL | 前端 `faySocket.ts` 使用 `ws://${location.host}`，自动适配 |
| 数字人组件 | DigitalHuman.vue 为 VRM-only (@pixiv/three-vrm) |
| Vite 配置 | 需 `resolve.dedupe:['three']` 防止多份 Three.js 冲突 |
| 关键词列表 | `FORCE_TOOL_PATTERNS` 根据业务场景增删关键词 |
| 工具 Prompt | `OMNI_TOOLS` 描述和 `instructions` 根据业务场景调整 |
