/**
 * 流式 PCM 24kHz 播放器 — 适配 Qwen3-Omni 实时音频输出
 */

export type AmplitudeCallback = (amp: number) => void;

interface PcmChunk {
  b64: string;
}

export class AudioPlayer {
  private ctx: AudioContext | null = null;
  private ampCallback: AmplitudeCallback | null = null;
  private _playing = false;
  private queue: PcmChunk[] = [];
  private nextPlayTime = 0;
  private readonly sampleRate = 24000;

  get playing() { return this._playing; }

  private ensureCtx(): AudioContext {
    if (!this.ctx) {
      this.ctx = new AudioContext({ sampleRate: this.sampleRate });
    }
    if (this.ctx.state === 'suspended') {
      this.ctx.resume();
    }
    return this.ctx;
  }

  /** 添加 PCM delta chunk */
  enqueue(b64: string) {
    this.queue.push({ b64 });
    if (!this._playing) {
      this._playing = true;
      this.nextPlayTime = this.ensureCtx().currentTime;
      this.dequeue();
    }
  }

  private dequeue() {
    if (this.queue.length === 0) {
      this._playing = false;
      this.ampCallback?.(0);
      return;
    }

    const chunk = this.queue.shift()!;
    const ctx = this.ensureCtx();

    try {
      // Base64 → Int16 samples
      const binary = atob(chunk.b64);
      const numSamples = binary.length / 2;
      const samples = new Int16Array(numSamples);
      for (let i = 0; i < binary.length; i++) {
        samples[i >> 1] |= (binary.charCodeAt(i) & 0xFF) << (8 * (i & 1));
      }

      // Calculate volume for jaw
      let sumSq = 0;
      for (let i = 0; i < samples.length; i++) {
        const n = samples[i] / 32768;
        sumSq += n * n;
      }
      const rms = Math.sqrt(sumSq / samples.length);
      this.ampCallback?.(Math.min(1, rms * 3.5));

      // Create Float32 buffer
      const floatBuf = new Float32Array(samples.length);
      for (let i = 0; i < samples.length; i++) {
        floatBuf[i] = samples[i] / 32768;
      }

      const audioBuf = ctx.createBuffer(1, samples.length, this.sampleRate);
      audioBuf.getChannelData(0).set(floatBuf);

      const source = ctx.createBufferSource();
      source.buffer = audioBuf;

      // Compute gain for smooth transitions
      const gain = ctx.createGain();
      gain.gain.setValueAtTime(0.8, ctx.currentTime);
      source.connect(gain);
      gain.connect(ctx.destination);

      const dur = samples.length / this.sampleRate;
      const startTime = Math.max(ctx.currentTime, this.nextPlayTime);
      source.start(startTime);
      this.nextPlayTime = startTime + dur;

      source.onended = () => {
        gain.disconnect();
        this.dequeue();
      };
    } catch (e) {
      console.error('AudioPlayer decode error:', e);
      this.dequeue();
    }
  }

  onAmplitude(cb: AmplitudeCallback) {
    this.ampCallback = cb;
  }

  reset() {
    this._playing = false;
    this.queue = [];
    this.nextPlayTime = 0;
    this.ampCallback?.(0);
  }

  stop() {
    this.reset();
    this.ctx?.close();
    this.ctx = null;
  }
}
