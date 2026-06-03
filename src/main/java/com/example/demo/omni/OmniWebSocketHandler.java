package com.example.demo.omni;

import com.example.demo.service.EmergencyDutyUiTools;
import com.example.demo.service.EmergencyEventUiTools;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class OmniWebSocketHandler implements WebSocketHandler {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String voice;
    private final String instructions;
    private final ContentRetriever contentRetriever;
    private final EmergencyDutyUiTools dutyTools;
    private final EmergencyEventUiTools eventTools;
    private final ConcurrentHashMap<String, DashScopeRealtimeClient> sessions = new ConcurrentHashMap<>();

    private static final List<Map<String, Object>> OMNI_TOOLS = List.of(
            Map.of(
                    "type", "function",
                    "name", "showDutyList",
                    "description", "显示当前XX值班表，包含值班人员姓名、岗位、联系电话、班次信息。当用户询问谁在值班、值班表、排班时调用。",
                    "parameters", Map.of("type", "object", "properties", Map.of(), "required", List.of())
            ),
            Map.of(
                    "type", "function",
                    "name", "showEventData",
                    "description", "显示XX事件数据面板，包含事件编号、类别、等级、状态、更新时间。当用户询问事件列表、警情、险情时调用。",
                    "parameters", Map.of("type", "object", "properties", Map.of(), "required", List.of())
            ),
            Map.of(
                    "type", "function",
                    "name", "search_knowledge_base",
                    "description", "搜索XX行业知识库获取专业知识。当用户询问行业管理、安全生产、防灾减灾、救援处置等专业知识时调用。",
                    "parameters", Map.of(
                            "type", "object",
                            "properties", Map.of("query", Map.of(
                                    "type", "string",
                                    "description", "在知识库中搜索的关键词或问题"
                            )),
                            "required", List.of("query")
                    )
            )
    );

    public OmniWebSocketHandler(
            @Value("${dashscope.api-key}") String apiKey,
            @Value("${dashscope.omni.model}") String model,
            @Value("${dashscope.omni.voice}") String voice,
            @Value("${dashscope.omni.instructions}") String instructions,
            ContentRetriever contentRetriever,
            EmergencyDutyUiTools dutyTools,
            EmergencyEventUiTools eventTools) {
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.instructions = instructions;
        this.contentRetriever = contentRetriever;
        this.dutyTools = dutyTools;
        this.eventTools = eventTools;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String sid = session.getHandshakeInfo().getUri().getQuery();
        if (sid != null && sid.startsWith("session_id=")) {
            sid = sid.substring("session_id=".length());
        } else {
            sid = session.getId();
        }

        // close old pipeline for same session
        DashScopeRealtimeClient old = sessions.remove(sid);
        if (old != null) old.close();

        // mutable holder so reconnect() can swap in a new client
        DashScopeRealtimeClient[] clientHolder = new DashScopeRealtimeClient[1];
        clientHolder[0] = new DashScopeRealtimeClient(apiKey, model, voice, instructions, OMNI_TOOLS);
        DashScopeRealtimeClient client = clientHolder[0];

        Sinks.Many<String> outbox = Sinks.many().unicast().onBackpressureBuffer();

        // wire callbacks — forward everything except "disconnected" state (DashScope idle timeout)
        client.onStateChange(state -> {
            if (!"disconnected".equals(state)) {
                outbox.tryEmitNext(json(Map.of("type", "state", "data", state)));
            }
        });
        client.onAudioDelta(b64 -> outbox.tryEmitNext(
                json(Map.of("type", "audio", "data", b64, "format", "pcm24k"))));
        client.onTextDelta(chunk -> outbox.tryEmitNext(
                json(Map.of("type", "text_chunk", "data", chunk))));
        client.onTextDone(full -> outbox.tryEmitNext(
                json(Map.of("type", "text_done", "data", full))));
        client.onSpeechText(text -> outbox.tryEmitNext(
                json(Map.of("type", "speech_text", "data", text))));
        client.onAudioDone(() -> outbox.tryEmitNext(
                json(Map.of("type", "audio_done"))));
        client.onError(msg -> outbox.tryEmitNext(
                json(Map.of("type", "error", "data", msg))));
        client.onFunctionCall((name, payload) -> handleFunctionCall(clientHolder[0], name, payload, outbox));

        // subscribe outbox → browser WS
        outbox.asFlux()
                .map(session::textMessage)
                .flatMap(msg -> session.send(Mono.just(msg)))
                .subscribe();

        Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();
        AtomicBoolean ready = new AtomicBoolean(false);
        CompletableFuture<Void> sessionEnd = new CompletableFuture<>();
        String finalSid = sid;

        // reconnect helper
        Runnable reconnect = () -> {
            DashScopeRealtimeClient newClient = new DashScopeRealtimeClient(
                    apiKey, model, voice, instructions, OMNI_TOOLS);
            // wire same callbacks into the new client
            newClient.onStateChange(state -> {
                if (!"disconnected".equals(state)) {
                    outbox.tryEmitNext(json(Map.of("type", "state", "data", state)));
                }
            });
            newClient.onAudioDelta(b64 -> outbox.tryEmitNext(
                    json(Map.of("type", "audio", "data", b64, "format", "pcm24k"))));
            newClient.onTextDelta(chunk -> outbox.tryEmitNext(
                    json(Map.of("type", "text_chunk", "data", chunk))));
            newClient.onTextDone(full -> outbox.tryEmitNext(
                    json(Map.of("type", "text_done", "data", full))));
            newClient.onSpeechText(text -> outbox.tryEmitNext(
                    json(Map.of("type", "speech_text", "data", text))));
            newClient.onAudioDone(() -> outbox.tryEmitNext(
                    json(Map.of("type", "audio_done"))));
            newClient.onError(msg -> outbox.tryEmitNext(
                    json(Map.of("type", "error", "data", msg))));
            newClient.onFunctionCall((name, payload) -> handleFunctionCall(newClient, name, payload, outbox));

            newClient.connect().thenAccept(v -> {
                newClient.initializeSession();
                // swap in the new client
                DashScopeRealtimeClient oldClient = clientHolder[0];
                clientHolder[0] = newClient;
                sessions.put(finalSid, newClient);
                oldClient.close();
                ready.set(true);
                // replay buffered messages
                String msg;
                while ((msg = pendingMessages.poll()) != null) {
                    dispatch(newClient, msg, finalSid);
                }
            }).exceptionally(err -> {
                outbox.tryEmitNext(json(Map.of("type", "error", "data", "重连DashScope失败: " + err.getMessage())));
                return null;
            });
        };

        // immediately subscribe to browser messages
        session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .subscribe(
                        raw -> {
                            // detect dead client (idle timeout etc.) and trigger reconnect
                            if (!clientHolder[0].isOpen() && ready.get()) {
                                ready.set(false);
                                pendingMessages.add(raw);
                                reconnect.run();
                                return;
                            }
                            if (ready.get()) {
                                dispatch(clientHolder[0], raw, finalSid);
                            } else {
                                pendingMessages.add(raw);
                            }
                        },
                        err -> sessionEnd.completeExceptionally(err),
                        () -> sessionEnd.complete(null)
                );

        // initial connection
        client.connect().thenAccept(v -> {
            client.initializeSession();
            sessions.put(finalSid, client);
            ready.set(true);
            String msg;
            while ((msg = pendingMessages.poll()) != null) {
                dispatch(client, msg, finalSid);
            }
        }).exceptionally(err -> {
            outbox.tryEmitNext(json(Map.of("type", "error", "data", "DashScope连接失败: " + err.getMessage())));
            sessionEnd.complete(null);
            return null;
        });

        return Mono.fromFuture(sessionEnd)
                .doFinally(sig -> {
                    sessions.remove(finalSid);
                    clientHolder[0].close();
                });
    }

    // --- Message dispatch from browser ---

    // 关键词 → 强制工具调用映射
    private static final String[][] FORCE_TOOL_PATTERNS = {
        {"值班", "排班", "谁在岗", "谁在上班", "今天谁", "明天谁", "这周谁", "值班表"},
        {"事件", "警情", "险情", "灾情", "事故", "突发"},
        {"知识", "规范", "预案", "标准", "怎么处置", "如何应对", "怎么办", "安全"}
    };
    private static final String[] FORCE_TOOL_NAMES = {
        "showDutyList",
        "showEventData",
        "search_knowledge_base"
    };

    @SuppressWarnings("unchecked")
    private void dispatch(DashScopeRealtimeClient client, String raw, String sessionId) {
        Map<String, Object> msg;
        try {
            msg = mapper.readValue(raw, Map.class);
        } catch (JsonProcessingException e) {
            return;
        }

        String type = (String) msg.getOrDefault("type", "");
        String data = (String) msg.getOrDefault("data", "");

        switch (type) {
            case "audio":
                client.sendAudio(data);
                break;
            case "text": {
                // 关键词检测：匹配到则强制告诉模型必须先调工具
                String prefix = "";
                for (int i = 0; i < FORCE_TOOL_PATTERNS.length; i++) {
                    for (String kw : FORCE_TOOL_PATTERNS[i]) {
                        if (data.contains(kw)) {
                            prefix = "【系统指令：你必须先调用" + FORCE_TOOL_NAMES[i] + "工具，再把结果告诉用户。不要自己编造信息。】";
                            break;
                        }
                    }
                    if (!prefix.isEmpty()) break;
                }
                client.sendText(prefix + data, null);
                break;
            }
            case "audio_done":
                client.commitAudio();
                break;
            case "cancel":
                client.cancelResponse();
                break;
            case "ping":
                break;
        }
    }

    // --- RAG retrieval (text mode) ---

    private String retrieveContext(String userText) {
        try {
            List<Content> contents = contentRetriever.retrieve(Query.from(userText));
            if (contents == null || contents.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("【知识库参考资料】\n");
            for (int i = 0; i < contents.size(); i++) {
                sb.append("---\n").append(contents.get(i).textSegment().text()).append("\n");
            }
            sb.append("---\n请基于以上参考资料回答用户问题。");
            return sb.toString();
        } catch (Exception e) {
            return null; // fail silently, model handles without RAG
        }
    }

    // --- Function call execution ---

    @SuppressWarnings("unchecked")
    private void handleFunctionCall(DashScopeRealtimeClient client, String name, Map<String, Object> payload,
                                     Sinks.Many<String> outbox) {
        String callId = (String) payload.get("call_id");
        Map<String, Object> args = (Map<String, Object>) payload.get("arguments");
        String output;

        switch (name) {
            case "showDutyList":
                output = dutyTools.showDutyList();
                outbox.tryEmitNext(json(Map.of("type", "show_panel", "data", "duty")));
                break;
            case "showEventData":
                output = eventTools.showEventData();
                outbox.tryEmitNext(json(Map.of("type", "show_panel", "data", "event")));
                break;
            case "search_knowledge_base": {
                String query = args != null ? (String) args.getOrDefault("query", "") : "";
                output = retrieveContext(query);
                if (output == null) output = "知识库中未找到相关信息。";
                break;
            }
            default:
                output = "Unknown tool: " + name;
        }

        client.sendFunctionCallOutput(callId, output);
    }

    // --- helpers ---

    private static String json(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
