/**
 * WebSocket 通信模块 — 连接 Fay 后端进行语音/文本对话
 */

export type WsState = 'disconnected' | 'connecting' | 'connected';

export interface WsInMessage {
  type: string;
  data?: string;
  format?: string;
}

export interface WsOutMessage {
  type: string;
  data?: string;
  format?: string;
  jawData?: number[];
  index?: number;
}

export type WsCallbacks = {
  onAudio?: (b64: string) => void;
  onAudioDone?: () => void;
  onTextChunk?: (chunk: string) => void;
  onTextDone?: (full: string) => void;
  onSpeechText?: (text: string) => void;
  onState?: (state: string) => void;
  onError?: (msg: string) => void;
  onStateChange?: (state: WsState) => void;
};

export class FaySocket {
  private ws: WebSocket | null = null;
  private callbacks: WsCallbacks = {};
  private _state: WsState = 'disconnected';
  private sessionId: string;
  private baseUrl: string;

  constructor(sessionId: string, baseUrl?: string) {
    this.sessionId = sessionId;
    this.baseUrl = baseUrl || `ws://${location.host}`;
  }

  get state() { return this._state; }

  setCallbacks(cbs: WsCallbacks) { this.callbacks = cbs; }

  connect(): Promise<void> {
    if (this._state === 'connected' || this._state === 'connecting') return Promise.resolve();
    this._state = 'connecting';
    this.callbacks.onStateChange?.('connecting');

    return new Promise((resolve, reject) => {
      const url = `${this.baseUrl}/ws/chat?session_id=${encodeURIComponent(this.sessionId)}`;
      try {
        this.ws = new WebSocket(url);
      } catch (e) {
        this._state = 'disconnected';
        reject(e);
        return;
      }

      this.ws.onopen = () => {
        this._state = 'connected';
        this.callbacks.onStateChange?.('connected');
        resolve();
      };

      this.ws.onmessage = (ev) => {
        try {
          const msg: WsOutMessage = JSON.parse(ev.data);
          switch (msg.type) {
            case 'audio':
              this.callbacks.onAudio?.(msg.data || '');
              break;
            case 'audio_done':
              this.callbacks.onAudioDone?.();
              break;
            case 'text_chunk':
              this.callbacks.onTextChunk?.(msg.data || '');
              break;
            case 'text_done':
              this.callbacks.onTextDone?.(msg.data || '');
              break;
            case 'speech_text':
              this.callbacks.onSpeechText?.(msg.data || '');
              break;
            case 'state':
              this.callbacks.onState?.(msg.data || '');
              break;
            case 'error':
              this.callbacks.onError?.(msg.data || '');
              break;
            case 'pong':
              break;
          }
        } catch { /* ignore parse errors */ }
      };

      this.ws.onclose = () => {
        this._state = 'disconnected';
        this.ws = null;
        this.callbacks.onStateChange?.('disconnected');
      };

      this.ws.onerror = () => {
        this._state = 'disconnected';
        this.callbacks.onStateChange?.('disconnected');
        reject(new Error('WebSocket connection failed'));
      };
    });
  }

  send(data: WsInMessage): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    }
  }

  sendText(text: string): void {
    this.send({ type: 'text', data: text });
  }

  sendAudio(b64: string, format = 'webm'): void {
    this.send({ type: 'audio', data: b64, format });
  }

  disconnect(): void {
    this.ws?.close();
    this.ws = null;
    this._state = 'disconnected';
  }
}
