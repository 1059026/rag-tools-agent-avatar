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

    @PostMapping("/new")
    public Mono<ResponseEntity<Void>> clearContext(@RequestParam String sessionId) {
        agentService.clearChatMemory(sessionId);
        return Mono.just(ResponseEntity.noContent().build());
    }
}
