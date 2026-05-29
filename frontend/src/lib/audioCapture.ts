/**
 * PCM 16kHz 音频采集 — 适配 Qwen3-Omni 实时 API
 * 使用 AudioContext + ScriptProcessorNode 获取原始 PCM 数据
 */

export type PcmCallback = (base64Pcm: string) => void;
export type StateCallback = (state: 'idle' | 'recording') => void;

export class AudioCapture {
  private ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private processor: ScriptProcessorNode | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private _state: 'idle' | 'recording' = 'idle';
  private onChunk: PcmCallback | null = null;
  private onState: StateCallback | null = null;
  // 累积 PCM buffer，每 100ms flush 一次
  private buffer: Int16Array = new Int16Array(0);
  private bufferPos = 0;
  private flushInterval: ReturnType<typeof setInterval> | null = null;
  private readonly targetSampleRate = 16000;
  private readonly flushMs = 100; // 100ms chunks

  get state() { return this._state; }

  async start(onChunk: PcmCallback, onState?: StateCallback): Promise<void> {
    if (this._state === 'recording') return;
    this.onChunk = onChunk;
    this.onState = onState || null;

    this.ctx = new AudioContext({ sampleRate: this.targetSampleRate });
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { sampleRate: this.targetSampleRate, channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });

    this.source = this.ctx.createMediaStreamSource(this.stream);

    // ScriptProcessorNode: bufferSize=4096 → ~256ms at 16kHz, we want smaller
    const bufferSize = 2048;
    this.processor = this.ctx.createScriptProcessor(bufferSize, 1, 1);
    this.buffer = new Int16Array(this.targetSampleRate * 2); // 2s buffer
    this.bufferPos = 0;

    this.processor.onaudioprocess = (e) => {
      const input = e.inputBuffer.getChannelData(0);
      // Convert Float32 [-1,1] → Int16 PCM
      for (let i = 0; i < input.length; i++) {
        const s = Math.max(-1, Math.min(1, input[i]));
        this.buffer[this.bufferPos++] = s < 0 ? s * 0x8000 : s * 0x7FFF;
      }
    };

    this.source.connect(this.processor);
    this.processor.connect(this.ctx.destination);

    // 定时 flush PCM 数据
    this.flushInterval = setInterval(() => {
      if (this.bufferPos > 0) {
        const chunk = this.buffer.slice(0, this.bufferPos);
        // Int16Array → base64
        const bytes = new Uint8Array(chunk.buffer);
        let b64 = '';
        for (let i = 0; i < bytes.length; i++) {
          b64 += String.fromCharCode(bytes[i]);
        }
        this.onChunk?.(btoa(b64));
        this.bufferPos = 0;
      }
    }, this.flushMs);

    this._state = 'recording';
    this.onState?.('recording');
  }

  stop(): void {
    if (this.flushInterval) { clearInterval(this.flushInterval); this.flushInterval = null; }
    // Flush remaining
    if (this.bufferPos > 0 && this.onChunk) {
      const chunk = this.buffer.slice(0, this.bufferPos);
      const bytes = new Uint8Array(chunk.buffer);
      let b64 = '';
      for (let i = 0; i < bytes.length; i++) b64 += String.fromCharCode(bytes[i]);
      this.onChunk(btoa(b64));
      this.bufferPos = 0;
    }
    this.processor?.disconnect();
    this.source?.disconnect();
    this.stream?.getTracks().forEach(t => t.stop());
    this.ctx?.close();
    this.ctx = null; this.stream = null; this.processor = null; this.source = null;
    this._state = 'idle';
    this.onState?.('idle');
  }

  cancel(): void { this.stop(); }
}
