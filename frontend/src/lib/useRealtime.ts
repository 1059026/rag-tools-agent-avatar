import { ref, shallowRef } from 'vue';

export type RealtimeState = 'disconnected' | 'connecting' | 'listening' | 'speaking';

export interface FunctionTool {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export function useRealtime() {
  const state = ref<RealtimeState>('disconnected');
  const transcript = ref('');
  const currentUserText = ref('');
  const jawAmplitude = ref(0);
  const audioPlaying = ref(false);
  const error = ref<string | null>(null);
  const responseVersion = ref(0);

  const registeredTools = shallowRef<FunctionTool[]>([]);

  let pc: RTCPeerConnection | null = null;
  let dc: RTCDataChannel | null = null;
  let localStream: MediaStream | null = null;
  let audioCtx: AudioContext | null = null;
  let analyserNode: AnalyserNode | null = null;
  let animFrame = 0;
  const functionHandlers = new Map<string, (args: unknown) => void>();

  // ── Connect ──────────────────────────────────────────────

  async function connect() {
    if (state.value !== 'disconnected') return;
    state.value = 'connecting';
    error.value = null;

    try {
      // 1. Fetch ephemeral token from backend
      const toolsPayload = registeredTools.value.map(t => ({
        type: 'function' as const,
        name: t.name,
        description: t.description,
        parameters: t.parameters,
      }));
      const tokenRes = await fetch('/api/realtime/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tools: toolsPayload }),
      });
      if (!tokenRes.ok) throw new Error(`Token fetch failed: ${await tokenRes.text()}`);
      const { token } = await tokenRes.json();

      // 2. Get microphone
      localStream = await navigator.mediaDevices.getUserMedia({ audio: true });

      // 3. Create peer connection
      pc = new RTCPeerConnection();

      // Add local audio track
      localStream.getTracks().forEach(t => pc!.addTrack(t, localStream!));

      // Handle remote (AI) audio — setup jaw analysis
      pc.ontrack = (event) => {
        const rs = event.streams[0];
        if (rs) setupAudioAnalysis(rs);
      };

      // 4. Create data channel
      dc = pc.createDataChannel('oai-events');
      dc.onopen = () => { state.value = 'listening'; };
      dc.onmessage = handleDcMessage;

      // 5. Create & send SDP offer
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      const sdpRes = await fetch(
        'https://api.openai.com/v1/realtime?model=gpt-realtime',
        {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/sdp',
          },
          body: offer.sdp,
        },
      );
      if (!sdpRes.ok) throw new Error(`SDP negotiation: ${await sdpRes.text()}`);
      await pc.setRemoteDescription({ type: 'answer', sdp: await sdpRes.text() });

      // Handle disconnection
      pc.oniceconnectionstatechange = () => {
        if (pc?.iceConnectionState === 'disconnected' || pc?.iceConnectionState === 'failed') {
          disconnect();
        }
      };
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      state.value = 'disconnected';
      cleanup();
    }
  }

  // ── Audio analysis for jaw animation ─────────────────────

  function setupAudioAnalysis(stream: MediaStream) {
    audioCtx = new AudioContext();
    analyserNode = audioCtx.createAnalyser();
    analyserNode.fftSize = 256;
    analyserNode.smoothingTimeConstant = 0.5;

    const src = audioCtx.createMediaStreamSource(stream);
    src.connect(analyserNode);
    src.connect(audioCtx.destination); // actually play the audio

    const data = new Uint8Array(analyserNode.fftSize);
    const loop = () => {
      if (!analyserNode) return;
      analyserNode.getByteTimeDomainData(data);
      let sumSq = 0;
      for (let i = 0; i < data.length; i++) {
        const n = (data[i] - 128) / 128;
        sumSq += n * n;
      }
      jawAmplitude.value = Math.sqrt(sumSq / data.length);
      audioPlaying.value = jawAmplitude.value > 0.01;
      animFrame = requestAnimationFrame(loop);
    };
    loop();
  }

  // ── Data channel message handling ────────────────────────

  function handleDcMessage(event: MessageEvent) {
    const msg = JSON.parse(event.data);

    switch (msg.type) {
      case 'input_audio_buffer.speech_started':
        state.value = 'listening';
        currentUserText.value = '';
        break;

      case 'input_audio_buffer.speech_stopped':
        break;

      case 'conversation.item.created':
        if (msg.item?.role === 'user' && msg.item?.content?.[0]?.transcript) {
          currentUserText.value = msg.item.content[0].transcript;
        }
        break;

      case 'response.audio_transcript.delta':
        if (msg.delta) transcript.value += msg.delta;
        break;

      case 'response.audio_transcript.done':
        transcript.value = msg.transcript || transcript.value;
        break;

      case 'response.function_call_arguments.done':
        state.value = 'speaking';
        handleFunctionCall(msg.call_id, msg.name, JSON.parse(msg.arguments || '{}'));
        break;

      case 'response.done':
        if (state.value === 'speaking') state.value = 'listening';
        break;

      case 'error':
        error.value = msg.error?.message || 'OpenAI Realtime error';
        break;

      case 'response.created':
        responseVersion.value++;
        break;

      case 'conversation.item.input_audio_transcription.completed':
      case 'session.created':
      case 'session.updated':
      case 'conversation.created':
      case 'rate_limits.updated':
        break;
    }
  }

  // ── Function calling ─────────────────────────────────────

  function handleFunctionCall(callId: string, name: string, args: unknown) {
    const handler = functionHandlers.get(name);
    if (handler) {
      handler(args);
    }
    // Send empty result back to keep conversation going
    sendFunctionResult(callId, { received: true });
  }

  function sendFunctionResult(callId: string, result: unknown) {
    if (!dc || dc.readyState !== 'open') return;
    dc.send(JSON.stringify({
      type: 'conversation.item.create',
      item: {
        type: 'function_call_output',
        call_id: callId,
        output: JSON.stringify(result),
      },
    }));
    dc.send(JSON.stringify({ type: 'response.create' }));
  }

  function onFunctionCall(name: string, handler: (args: unknown) => void) {
    functionHandlers.set(name, handler);
  }

  // ── Tool registration ────────────────────────────────────

  function registerTool(tool: FunctionTool) {
    if (registeredTools.value.find(t => t.name === tool.name)) return;
    registeredTools.value = [...registeredTools.value, tool];
  }

  // ── Disconnect ───────────────────────────────────────────

  function disconnect() {
    cleanup();
    state.value = 'disconnected';
    transcript.value = '';
    currentUserText.value = '';
    jawAmplitude.value = 0;
    audioPlaying.value = false;
  }

  function cleanup() {
    cancelAnimationFrame(animFrame);
    audioCtx?.close();
    audioCtx = null;
    analyserNode = null;
    dc?.close();
    dc = null;
    pc?.close();
    pc = null;
    if (localStream) {
      localStream.getTracks().forEach(t => t.stop());
      localStream = null;
    }
  }

  // ── Manual text input (keyboard fallback) ────────────────

  function sendTextMessage(text: string) {
    if (!dc || dc.readyState !== 'open') return;
    const clean = text.trim();
    if (clean.length < 1) return;

    // Show as user message
    currentUserText.value = clean;

    // Send to OpenAI
    dc.send(JSON.stringify({
      type: 'conversation.item.create',
      item: {
        type: 'message',
        role: 'user',
        content: [{ type: 'input_text', text: clean }],
      },
    }));
    dc.send(JSON.stringify({ type: 'response.create' }));
  }

  return {
    state,
    transcript,
    currentUserText,
    jawAmplitude,
    audioPlaying,
    error,
    responseVersion,
    connect,
    disconnect,
    onFunctionCall,
    registerTool,
    sendTextMessage,
  };
}
