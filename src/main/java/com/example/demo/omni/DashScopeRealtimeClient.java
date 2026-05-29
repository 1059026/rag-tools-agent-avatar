package com.example.demo.omni;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Java WebSocket client for DashScope Qwen3-Omni realtime API.
 * Uses org.java-websocket for reliable WebSocket communication.
 */
public class DashScopeRealtimeClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String voice;
    private final String instructions;
    private final List<Map<String, Object>> tools;
    private WsClient ws;
    private final StringBuilder textAccum = new StringBuilder();

    // --- callbacks ---
    private Consumer<String> onAudioDelta;
    private Consumer<String> onTextDelta;
    private Consumer<String> onTextDone;
    private Consumer<String> onSpeechText;
    private Consumer<String> onStateChange;
    private Consumer<String> onError;
    private Runnable onAudioDone;
    private java.util.function.BiConsumer<String, Map<String, Object>> onFunctionCall;

    public DashScopeRealtimeClient(String apiKey, String model, String voice,
                                   String instructions, List<Map<String, Object>> tools) {
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.instructions = instructions;
        this.tools = tools;
    }

    // --- callback setters ---

    public void onAudioDelta(Consumer<String> cb) { this.onAudioDelta = cb; }
    public void onTextDelta(Consumer<String> cb) { this.onTextDelta = cb; }
    public void onTextDone(Consumer<String> cb) { this.onTextDone = cb; }
    public void onSpeechText(Consumer<String> cb) { this.onSpeechText = cb; }
    public void onStateChange(Consumer<String> cb) { this.onStateChange = cb; }
    public void onError(Consumer<String> cb) { this.onError = cb; }
    public void onAudioDone(Runnable cb) { this.onAudioDone = cb; }
    public void onFunctionCall(java.util.function.BiConsumer<String, Map<String, Object>> cb) { this.onFunctionCall = cb; }

    // --- connection ---

    /** Connect to DashScope. Returns a future that completes when the WebSocket is open. */
    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> ready = new CompletableFuture<>();

        String url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=" + model;
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + apiKey,
                "User-Agent", "fay-java-omni/1.0"
        );

        try {
            ws = new WsClient(URI.create(url), headers, ready);
            ws.connect();
        } catch (Exception e) {
            ready.completeExceptionally(e);
        }

        return ready;
    }

    /** Initialize the session AFTER connect() completes. Runs session setup on a fresh thread. */
    public void initializeSession() {
        new Thread(() -> {
            try {
                Thread.sleep(300); // let session.created arrive (matching Python)
            } catch (InterruptedException ignored) {}

            emitState("connected");

            Map<String, Object> session = new LinkedHashMap<>();
            session.put("modalities", List.of("text", "audio"));
            session.put("voice", voice);
            session.put("instructions", instructions);
            session.put("input_audio_format", "pcm16");
            session.put("output_audio_format", "pcm16");
            Map<String, Object> transcription = new java.util.HashMap<>();
            transcription.put("model", null);
            session.put("input_audio_transcription", transcription);
            Map<String, Object> turnDetection = new LinkedHashMap<>();
            turnDetection.put("type", "server_vad");
            turnDetection.put("threshold", 0.2);
            turnDetection.put("prefix_padding_ms", 300);
            turnDetection.put("silence_duration_ms", 800);
            session.put("turn_detection", turnDetection);

            sendJson(Map.of(
                    "event_id", eventId(),
                    "type", "session.update",
                    "session", session
            ));

            try { Thread.sleep(200); } catch (InterruptedException ignored) {}

            if (tools != null && !tools.isEmpty()) {
                sendJson(Map.of(
                        "event_id", eventId(),
                        "type", "session.update",
                        "session", Map.of("tools", tools)
                ));
            }
        }, "dashscope-init").start();
    }

    // --- outgoing messages ---

    public void sendAudio(String base64Pcm16) {
        sendJson(Map.of(
                "event_id", eventId(),
                "type", "input_audio_buffer.append",
                "audio", base64Pcm16
        ));
    }

    public void sendText(String text, String ragContext) {
        String content = (ragContext != null && !ragContext.isEmpty())
                ? ragContext + "\n\n用户问题：" + text
                : text;

        sendJson(Map.of(
                "event_id", eventId(),
                "type", "conversation.item.create",
                "item", Map.of(
                        "type", "message",
                        "role", "user",
                        "content", List.of(Map.of("type", "input_text", "text", content))
                )
        ));
        sendJson(Map.of(
                "event_id", eventId(),
                "type", "response.create",
                "response", Map.of()
        ));
    }

    public void commitAudio() {
        sendJson(Map.of(
                "event_id", eventId(),
                "type", "input_audio_buffer.commit"
        ));
        sendJson(Map.of(
                "event_id", eventId(),
                "type", "response.create",
                "response", Map.of()
        ));
    }

    public void cancelResponse() {
        sendJson(Map.of(
                "event_id", eventId(),
                "type", "response.cancel"
        ));
    }

    public void sendFunctionCallOutput(String callId, String output) {
        sendJson(Map.of(
                "type", "conversation.item.create",
                "item", Map.of(
                        "type", "function_call_output",
                        "call_id", callId,
                        "output", output
                )
        ));
        sendJson(Map.of(
                "event_id", eventId(),
                "type", "response.create",
                "response", Map.of()
        ));
    }

    public boolean isOpen() {
        return ws != null && ws.isOpen();
    }

    public void close() {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
            ws = null;
        }
    }

    // --- helpers ---

    private synchronized void sendJson(Object obj) {
        if (ws == null || !ws.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(obj);
            ws.send(json);
        } catch (JsonProcessingException e) {
            emitError("JSON serialize: " + e.getMessage());
        }
    }

    private static String eventId() { return "event_" + UUID.randomUUID().toString().replace("-", ""); }

    private void emitState(String s) { if (onStateChange != null) onStateChange.accept(s); }
    private void emitError(String s) { if (onError != null) onError.accept(s); }

    // --- WebSocket client using org.java-websocket ---

    private class WsClient extends WebSocketClient {

        private final CompletableFuture<Void> ready;

        WsClient(URI uri, Map<String, String> headers, CompletableFuture<Void> ready) {
            super(uri, headers);
            this.ready = ready;
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            ready.complete(null);
        }

        @Override
        public void onMessage(String message) {
            processEvent(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            emitState("disconnected");
        }

        @Override
        public void onError(Exception ex) {
            if (!ready.isDone()) {
                ready.completeExceptionally(ex);
            } else {
                emitError(ex.getMessage());
            }
        }
    }

    // --- event processing ---

    @SuppressWarnings("unchecked")
    private void processEvent(String raw) {
        Map<String, Object> ev;
        try {
            ev = mapper.readValue(raw, Map.class);
        } catch (JsonProcessingException e) {
            return;
        }

        String type = (String) ev.get("type");
        if (type == null) return;

        switch (type) {
            case "response.audio.delta": {
                String delta = (String) ev.get("delta");
                if (delta != null && onAudioDelta != null) onAudioDelta.accept(delta);
                break;
            }
            case "response.audio_transcript.delta":
            case "response.text.delta": {
                String delta = (String) ev.get("delta");
                if (delta != null) {
                    textAccum.append(delta);
                    if (onTextDelta != null) onTextDelta.accept(delta);
                }
                break;
            }
            case "response.audio_transcript.done":
            case "response.text.done": {
                String transcript = (String) ev.get("transcript");
                String text = (String) ev.get("text");
                String final_ = (transcript != null) ? transcript : (text != null ? text : textAccum.toString());
                if (!final_.isEmpty() && onTextDone != null) onTextDone.accept(final_);
                textAccum.setLength(0);
                break;
            }
            case "conversation.item.input_audio_transcription.completed": {
                String transcript = (String) ev.get("transcript");
                if (transcript != null && onSpeechText != null) onSpeechText.accept(transcript);
                break;
            }
            case "response.function_call_arguments.done": {
                String callId = (String) ev.get("call_id");
                String name = (String) ev.get("name");
                Map<String, Object> args;
                try {
                    String argsJson = (String) ev.get("arguments");
                    args = (argsJson != null) ? mapper.readValue(argsJson, Map.class) : Map.of();
                } catch (JsonProcessingException e) {
                    args = Map.of();
                }
                if (onFunctionCall != null) onFunctionCall.accept(name,
                        Map.of("call_id", callId, "name", name, "arguments", args));
                break;
            }
            case "response.done": {
                if (onAudioDone != null) onAudioDone.run();
                break;
            }
            case "input_audio_buffer.speech_started":
                emitState("listening");
                break;
            case "input_audio_buffer.speech_stopped":
                emitState("llm");
                break;
            case "error":
                emitError((String) ev.getOrDefault("message", raw));
                break;
        }
    }
}
