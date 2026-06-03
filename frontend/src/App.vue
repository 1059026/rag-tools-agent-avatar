<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { FaySocket } from "./lib/faySocket";
import { AudioPlayer } from "./lib/audioPlayer";
import { AudioCapture } from "./lib/audioCapture";
import type { WsState } from "./lib/faySocket";
import DigitalHuman from "./components/DigitalHuman.vue";
import type { DhState } from "./components/DigitalHuman.vue";

const SESSION_KEY = "demo-chat-session-id";

type Role = "user" | "assistant";

interface Msg {
  id: string;
  role: Role;
  text: string;
}

// ── Session & state ──────────────────────────────────────

const sessionId = ref("");
const input = ref("");
const messages = ref<Msg[]>([]);
const sending = ref(false);
const firstChunkReceived = ref(false);
const error = ref<string | null>(null);
const listEl = ref<HTMLElement | null>(null);

// 语音模式
const voiceMode = ref(false);
const recording = ref(false);
const wsState = ref<WsState>("disconnected");
const pipelineState = ref(""); // "asr" | "llm" | "tts"
const dhJawAmplitude = ref(0);
// avatarIndex / avatarLabels removed — VRM model doesn't need them

let faySocket: FaySocket | null = null;
const audioCapture = new AudioCapture();
const audioPlayer = new AudioPlayer();

// ── DH State ─────────────────────────────────────────────

const dhState = computed<DhState>(() => {
  if (error.value) return "error";
  if (voiceMode.value && pipelineState.value === "listening") return "listening";
  if (pipelineState.value === "speaking") return "speaking";
  if (sending.value && !firstChunkReceived.value) return "thinking";
  if (sending.value && firstChunkReceived.value) return "speaking";
  return "idle";
});

// ── Session helpers ──────────────────────────────────────

function ensureSession(): string {
  let id = sessionStorage.getItem(SESSION_KEY);
  if (!id) {
    id = crypto.randomUUID();
    sessionStorage.setItem(SESSION_KEY, id);
  }
  return id;
}

function newSession() {
  const id = crypto.randomUUID();
  sessionStorage.setItem(SESSION_KEY, id);
  sessionId.value = id;
  messages.value = [];
  error.value = null;
}

const UI_MARKER_REGEX = /\[{1,2}UI:(DUTY_LIST|EVENT_DATA)\]{1,2}/g;

function stripUiMarkers(raw: string): string {
  return raw.replace(UI_MARKER_REGEX, "");
}

function onAssistantStreamChunk(
  rawAccum: string,
  setDisplayText: (cleaned: string) => void
): void {
  if (rawAccum.includes("[UI:DUTY_LIST]")) {
    showEmergencyDutyPanel.value = true;
  }
  if (rawAccum.includes("[UI:EVENT_DATA]")) {
    showEmergencyEventDataPanel.value = true;
  }
  setDisplayText(stripUiMarkers(rawAccum));
}

// ── Emergency panels ─────────────────────────────────────

const showEmergencyDutyPanel = ref(false);
const showEmergencyEventDataPanel = ref(false);

const anyPanelOpen = computed(
  () => showEmergencyDutyPanel.value || showEmergencyEventDataPanel.value
);

const emergencyDutyRows = [
  { name: "张伟", role: "带班负责人", phone: "138****1001", shift: "5月4日 08:00–20:00" },
  { name: "李娜", role: "值班员", phone: "139****2002", shift: "5月4日 08:00–20:00" },
  { name: "王强", role: "XX联络员", phone: "137****3003", shift: "5月4日 20:00–次日08:00" },
];

const emergencyEventRows = [
  { code: "EVT-2026-0504-01", category: "危化泄漏", level: "III 级", status: "处置中", updatedAt: "2026-05-04 09:42" },
  { code: "EVT-2026-0503-12", category: "森林火情", level: "IV 级", status: "已控制", updatedAt: "2026-05-03 18:10" },
  { code: "EVT-2026-0502-03", category: "城市内涝", level: "IV 级", status: "已结束", updatedAt: "2026-05-02 11:05" },
];

function closeAllPanels(): void {
  showEmergencyDutyPanel.value = false;
  showEmergencyEventDataPanel.value = false;
}

function closeTopPanelFromEsc(): void {
  if (showEmergencyEventDataPanel.value) {
    showEmergencyEventDataPanel.value = false;
    return;
  }
  if (showEmergencyDutyPanel.value) {
    showEmergencyDutyPanel.value = false;
  }
}

watch(anyPanelOpen, (open, _prev, onCleanup) => {
  if (!open) return;
  const onKey = (e: KeyboardEvent) => {
    if (e.key === "Escape") closeTopPanelFromEsc();
  };
  window.addEventListener("keydown", onKey);
  onCleanup(() => window.removeEventListener("keydown", onKey));
});

// ── Audio player amplitude → DH jaw ──────────────────────

audioPlayer.onAmplitude((amp) => {
  dhJawAmplitude.value = amp;
});

// ── Voice mode WebSocket setup ───────────────────────────

let assistantAcc = "";
let audioStarted = false;

function flushAssistantText() {
  const idx = messages.value.findIndex(m => m.id === currentAsstId);
  if (idx >= 0) {
    onAssistantStreamChunk(assistantAcc, (cleaned) => {
      messages.value[idx]!.text = cleaned;
    });
  }
}

function setupSocket() {
  const sid = sessionId.value || ensureSession();
  sessionId.value = sid;
  faySocket = new FaySocket(sid);

  faySocket.setCallbacks({
    onStateChange: (s) => { wsState.value = s; },
    onState: (s) => { pipelineState.value = s; },
    onTextChunk: (chunk) => {
      if (!firstChunkReceived.value) {
        firstChunkReceived.value = true;
        pipelineState.value = 'llm';
        assistantAcc = "";
        audioStarted = false;
      }
      assistantAcc += chunk;
      if (audioStarted) {
        flushAssistantText();
      }
      scrollToBottom();
    },
    onTextDone: () => {
      // 兜底：如果音频始终没来（纯文本响应），释放暂存文字
      if (!audioStarted) {
        audioStarted = true;
        flushAssistantText();
      }
      sending.value = false;
      firstChunkReceived.value = false;
      pipelineState.value = '';
      scrollToBottom();
    },
    onSpeechText: (text: string) => {
      // Omni 返回的用户语音识别文本
      messages.value.push({ id: crypto.randomUUID(), role: "user", text });
      currentAsstId = crypto.randomUUID();
      messages.value.push({ id: currentAsstId, role: "assistant", text: "" });
      sending.value = true;
      firstChunkReceived.value = false;
      assistantAcc = "";
      audioStarted = false;
      audioPlayer.reset();
      scrollToBottom();
    },
    onAudio: (b64: string) => {
      if (!audioStarted) {
        audioStarted = true;
        flushAssistantText();
      }
      audioPlayer.enqueue(b64);
      pipelineState.value = 'speaking';
    },
    onAudioDone: () => {
      sending.value = false;
      pipelineState.value = '';
    },
    onShowPanel: (panel: string) => {
      if (panel === 'duty') showEmergencyDutyPanel.value = true;
      else if (panel === 'event') showEmergencyEventDataPanel.value = true;
    },
    onError: (msg) => {
      error.value = msg;
      sending.value = false;
      recording.value = false;
      pipelineState.value = '';
    },
  });
}

let currentAsstId = "";

async function toggleVoiceMode() {
  voiceMode.value = !voiceMode.value;
  if (voiceMode.value) {
    if (!faySocket) setupSocket();
    await faySocket!.connect();
    startVoiceStream();
  } else {
    stopVoiceStream();
  }
}

function startVoiceStream() {
  recording.value = true;
  error.value = null;
  audioPlayer.reset();
  audioCapture.start(
    (b64) => {
      faySocket!.send({ type: 'audio', data: b64, format: 'pcm16' });
    },
    (state) => {
      if (state === 'recording') pipelineState.value = '';
    },
  );
}

function stopVoiceStream() {
  audioCapture.cancel();
  audioPlayer.stop();
  recording.value = false;
  sending.value = false;
  dhJawAmplitude.value = 0;
  pipelineState.value = '';
}

function interruptResponse() {
  // 打断当前模型回复
  faySocket!.send({ type: 'cancel' });
  audioPlayer.reset();
  sending.value = false;
  pipelineState.value = '';
}

// ── Text mode (SSE) ──────────────────────────────────────

const canSend = computed(
  () => !sending.value && input.value.trim().length > 0
);

async function scrollToBottom() {
  await nextTick();
  const el = listEl.value;
  if (el) el.scrollTop = el.scrollHeight;
}

async function clearContextAndRotateSession(): Promise<boolean> {
  const sid = sessionId.value || ensureSession();
  sessionId.value = sid;
  error.value = null;
  try {
    const res = await fetch(
      `/api/agent/new?sessionId=${encodeURIComponent(sid)}`,
      { method: "POST" }
    );
    if (!res.ok) {
      throw new Error(`${res.status} ${await res.text()}`);
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
    return false;
  }
  newSession();
  return true;
}

onMounted(async () => {
  sessionId.value = ensureSession();
  const path = window.location.pathname.replace(/\/$/, "") || "/";
  if (path === "/new") {
    const ok = await clearContextAndRotateSession();
    if (ok) {
      window.history.replaceState({}, "", "/");
    }
  }
});

async function send() {
  const text = input.value.trim();
  if (!text || sending.value) return;

  // 确保 WebSocket 连接（文本模式复用）
  if (!faySocket || wsState.value !== "connected") {
    if (!faySocket) setupSocket();
    await faySocket!.connect();
  }

  error.value = null;
  sending.value = true;
  firstChunkReceived.value = false;

  const userMsg: Msg = { id: crypto.randomUUID(), role: "user", text };
  messages.value.push(userMsg);

  currentAsstId = crypto.randomUUID();
  messages.value.push({ id: currentAsstId, role: "assistant", text: "" });
  input.value = "";
  audioPlayer.reset();
  await scrollToBottom();

  faySocket!.sendText(text);
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    void send();
  }
}
</script>

<template>
  <div class="app-shell">
    <aside class="avatar-panel">
      <DigitalHuman :state="dhState" :jawAmplitude="dhJawAmplitude" />
    </aside>
    <div class="chat-panel">
      <div class="layout">
        <header class="toolbar">
          <div class="session">
            <span class="label">会话 ID</span>
            <code class="sid" :title="sessionId">{{ sessionId }}</code>
            <button type="button" class="btn ghost" @click="newSession">新会话</button>
            <a class="btn ghost link-new" href="/new" title="清空服务端对话记忆并轮换会话 ID">/new</a>
          </div>

          <!-- 语音/文本模式切换 -->
          <div class="mode-bar">
            <button type="button" class="btn mode-btn" :class="{ active: !voiceMode }" @click="voiceMode && toggleVoiceMode()">文本模式</button>
            <button type="button" class="btn mode-btn" :class="{ active: voiceMode }" @click="!voiceMode && toggleVoiceMode()">语音模式</button>
            <span v-if="voiceMode" class="ws-badge" :class="wsState">WS: {{ wsState }}</span>
            <span v-if="pipelineState" class="pipeline-badge">{{ pipelineState }}</span>
            <span class="mode-sep">| VRM Keito</span>
          </div>

          <p class="hint">
            访问 <code class="inline-code">/new</code> 会调用 <code class="inline-code">POST /api/agent/new</code> 清理服务端上下文并生成本地新会话。
          </p>
        </header>

        <main ref="listEl" class="messages">
          <div v-if="messages.length === 0" class="empty">输入内容开始对话，或开启语音模式</div>
          <article
            v-for="m in messages"
            :key="m.id"
            class="bubble"
            :data-role="m.role"
          >
            <span class="who">{{ m.role === "user" ? "你" : "助手" }}</span>
            <pre class="text">{{ m.text }}</pre>
          </article>
        </main>

        <p v-if="error" class="err">{{ error }}</p>

        <footer class="composer">
          <!-- 语音模式：录音中 / 打断按钮 -->
          <template v-if="voiceMode">
            <button
              v-if="pipelineState === 'speaking' || (sending && firstChunkReceived)"
              type="button"
              class="btn record-btn interrupting"
              @click="interruptResponse"
            >
              打断
            </button>
            <button
              v-else
              type="button"
              class="btn record-btn"
              :class="{ recording }"
              disabled
            >
              {{ pipelineState === 'listening' ? '正在听...' : '麦克风已开' }}
            </button>
          </template>

          <!-- 文本输入区 -->
          <textarea
            v-if="!voiceMode"
            v-model="input"
            class="field"
            rows="3"
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            :disabled="sending"
            @keydown="onKeydown"
          />
          <button
            v-if="!voiceMode"
            type="button"
            class="btn primary"
            :disabled="!canSend"
            @click="send()"
          >
            {{ sending ? "生成中…" : "发送" }}
          </button>
        </footer>
      </div>

      <!-- Emergency panels -->
      <Teleport to="body">
        <div
          v-if="anyPanelOpen"
          class="duty-modal-backdrop"
          role="presentation"
          @click.self="closeAllPanels"
        >
          <div class="duty-modal-stack">
            <div v-if="showEmergencyDutyPanel" class="duty-modal" role="dialog" aria-modal="true" aria-labelledby="duty-modal-title" @click.stop>
              <div class="duty-panel-head">
                <h2 id="duty-modal-title" class="duty-title">XX值班表</h2>
                <button type="button" class="btn ghost duty-close" @click="showEmergencyDutyPanel = false">关闭</button>
              </div>
              <div class="duty-table-wrap">
                <table class="duty-table">
                  <thead>
                    <tr><th>姓名</th><th>岗位</th><th>联系电话</th><th>班次</th></tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, i) in emergencyDutyRows" :key="i">
                      <td>{{ row.name }}</td><td>{{ row.role }}</td><td>{{ row.phone }}</td><td>{{ row.shift }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p class="duty-footnote">Esc 先关本面板；两面板同开时点遮罩全部关闭</p>
            </div>
            <div v-if="showEmergencyEventDataPanel" class="duty-modal duty-modal--events" role="dialog" aria-modal="true" aria-labelledby="event-modal-title" @click.stop>
              <div class="duty-panel-head">
                <h2 id="event-modal-title" class="duty-title">XX事件数据</h2>
                <button type="button" class="btn ghost duty-close" @click="showEmergencyEventDataPanel = false">关闭</button>
              </div>
              <div class="duty-table-wrap">
                <table class="duty-table">
                  <thead>
                    <tr><th>事件编号</th><th>类别</th><th>等级</th><th>状态</th><th>更新时间</th></tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, i) in emergencyEventRows" :key="i">
                      <td>{{ row.code }}</td><td>{{ row.category }}</td><td>{{ row.level }}</td><td>{{ row.status }}</td><td>{{ row.updatedAt }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p class="duty-footnote">工具 showEventData；可与值班面板同时打开以测解析干扰</p>
            </div>
          </div>
        </div>
      </Teleport>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.avatar-panel {
  flex: 0 0 42%;
  min-width: 320px;
  max-width: 560px;
  padding: 12px;
}

.chat-panel {
  flex: 1;
  min-width: 380px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.layout {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: 640px;
  margin: 0 auto;
  padding: 12px;
  gap: 8px;
  width: 100%;
}

@media (max-width: 800px) {
  .app-shell { flex-direction: column; }
  .avatar-panel { flex: 0 0 35%; max-width: none; min-width: 0; }
  .chat-panel { flex: 1; min-width: 0; }
}

/* ── Mode bar ─────────────── */
.mode-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.mode-btn {
  padding: 4px 14px;
  border-radius: 14px;
  font-size: 12px;
  border: 1px solid #38444d;
  background: transparent;
  color: #71767b;
  cursor: pointer;
}

.mode-btn.active {
  background: #1d9bf0;
  color: #fff;
  border-color: #1d9bf0;
}

.ws-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 8px;
  background: #16181c;
  color: #71767b;
}

.ws-badge.connected { color: #00ba7c; }
.ws-badge.connecting { color: #f59e0b; }
.ws-badge.disconnected { color: #f4212e; }

.mode-sep { color: #2f3336; margin: 0 2px; }

.pipeline-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 8px;
  background: #1a3a52;
  color: #1d9bf0;
  text-transform: uppercase;
}

/* ── Record button ────────── */
.record-btn {
  flex: 1;
  padding: 14px;
  font-size: 16px;
  border-radius: 12px;
  border: 2px solid #2f3336;
  background: #16181c;
  color: inherit;
  cursor: pointer;
}

.record-btn.recording {
  border-color: #f4212e;
  background: #2a1515;
  color: #f4212e;
  animation: pulse-rec 1.5s ease-in-out infinite;
}

@keyframes pulse-rec {
  0%, 100% { box-shadow: 0 0 0 0 rgba(244, 33, 46, 0.4); }
  50% { box-shadow: 0 0 0 8px rgba(244, 33, 46, 0); }
}

/* ── Rest (same as before) ── */
.duty-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(2px);
}

.duty-modal-stack {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  width: 100%;
  max-height: min(90vh, 900px);
  overflow-y: auto;
}

.duty-modal {
  width: min(560px, 100%);
  flex-shrink: 0;
  max-height: min(70vh, 560px);
  overflow: auto;
  border-radius: 14px;
  border: 1px solid #2b5775;
  background: linear-gradient(180deg, #15232f 0%, #1a252e 100%);
  padding: 16px 18px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.5);
}

.duty-modal--events {
  border-color: #8b5a2b;
  background: linear-gradient(180deg, #2a2218 0%, #1f1c18 100%);
}

.link-new { text-decoration: none; display: inline-flex; align-items: center; }

.inline-code {
  font-size: 10px;
  padding: 1px 5px;
  background: #16181c;
  border-radius: 4px;
}

.duty-panel-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.duty-title { margin: 0; font-size: 16px; font-weight: 600; color: #e7f0f7; }
.duty-close { flex-shrink: 0; }
.duty-table-wrap { overflow-x: auto; border-radius: 8px; border: 1px solid #2f3336; background: #16181c; }
.duty-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.duty-table th, .duty-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid #2f3336; }
.duty-table th { color: #8b98a5; font-weight: 600; background: #1a1d21; }
.duty-table tbody tr:last-child td { border-bottom: none; }
.duty-table td { color: #e7e9ea; }
.duty-footnote { margin: 12px 0 0; font-size: 11px; color: #536471; }

.toolbar { display: flex; flex-direction: column; gap: 6px; padding-bottom: 8px; border-bottom: 1px solid #2f3336; }
.session { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.label { font-size: 12px; color: #71767b; }
.sid {
  flex: 1; min-width: 0; font-size: 11px; padding: 4px 8px;
  background: #16181c; border-radius: 6px; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.hint { margin: 0; font-size: 11px; color: #536471; }
.messages { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; padding: 8px 0; min-height: 0; }
.empty { color: #71767b; font-size: 14px; text-align: center; margin-top: 24px; }
.bubble { align-self: flex-start; max-width: 92%; padding: 10px 12px; border-radius: 12px; background: #1d2124; border: 1px solid #2f3336; }
.bubble[data-role="user"] { align-self: flex-end; background: #1a3a52; border-color: #2b5775; }
.who { display: block; font-size: 11px; color: #71767b; margin-bottom: 4px; }
.text { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 14px; line-height: 1.45; font-family: inherit; }
.err { margin: 0; font-size: 13px; color: #f4212e; }
.composer { display: flex; gap: 8px; align-items: flex-end; padding-top: 8px; border-top: 1px solid #2f3336; }
.field {
  flex: 1; resize: none; border-radius: 10px; border: 1px solid #2f3336;
  background: #16181c; color: inherit; padding: 10px 12px; font: inherit;
}
.field:focus { outline: none; border-color: #1d9bf0; }
.btn { border-radius: 999px; padding: 10px 18px; font: inherit; cursor: pointer; border: none; flex-shrink: 0; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.primary { background: #1d9bf0; color: #fff; }
.btn.ghost { background: transparent; color: #1d9bf0; border: 1px solid #38444d; padding: 6px 12px; border-radius: 8px; font-size: 13px; }
</style>
