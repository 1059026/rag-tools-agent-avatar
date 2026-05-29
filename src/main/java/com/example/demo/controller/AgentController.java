package com.example.demo.controller;

import com.example.demo.service.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @Autowired
    private AgentService agentService;

    @GetMapping(value = "/ping", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ping(
            @RequestParam String sessionId,
            @RequestParam String message) {
        return agentService.ping(sessionId, message);
    }

    /** 清理当前 sessionId 对应的服务端对话记忆，不结束会话；前端可随后轮换 sessionId 并清空本地消息列表 */
    @PostMapping("/new")
    public Mono<ResponseEntity<Void>> clearContext(@RequestParam String sessionId) {
        agentService.clearChatMemory(sessionId);
        return Mono.just(ResponseEntity.noContent().build());
    }
}
