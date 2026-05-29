/**
 * 浏览器内置语音识别 — Web Speech API
 */

export type SpeechResultCallback = (text: string, isFinal: boolean) => void;
export type SpeechStateCallback = (state: 'idle' | 'listening' | 'error', error?: string) => void;

export class BrowserSpeechRecognition {
  private recognition: any = null;
  private _state: 'idle' | 'listening' | 'error' = 'idle';

  get state() { return this._state; }

  start(
    onResult: SpeechResultCallback,
    onState?: SpeechStateCallback,
    lang = 'zh-CN',
  ): void {
    const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SR) {
      const msg = '浏览器不支持语音识别（需要 Chrome/Edge）';
      this._state = 'error';
      onState?.('error', msg);
      return;
    }

    this.recognition = new SR();
    this.recognition.lang = lang;
    this.recognition.interimResults = true;
    this.recognition.continuous = true;
    this.recognition.maxAlternatives = 1;

    this.recognition.onresult = (event: any) => {
      let interim = '';
      let final = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const r = event.results[i];
        if (r.isFinal) {
          final += r[0].transcript;
        } else {
          interim += r[0].transcript;
        }
      }
      if (final) onResult(final, true);
      else if (interim) onResult(interim, false);
    };

    this.recognition.onerror = (event: any) => {
      this._state = 'error';
      onState?.('error', event.error);
    };

    this.recognition.onstart = () => {
      this._state = 'listening';
      onState?.('listening');
    };

    this.recognition.onend = () => {
      if (this._state === 'listening') {
        this._state = 'idle';
        onState?.('idle');
      }
    };

    this.recognition.start();
  }

  stop(): void {
    this.recognition?.stop();
    this._state = 'idle';
  }

  abort(): void {
    this.recognition?.abort();
    this._state = 'idle';
  }
}
