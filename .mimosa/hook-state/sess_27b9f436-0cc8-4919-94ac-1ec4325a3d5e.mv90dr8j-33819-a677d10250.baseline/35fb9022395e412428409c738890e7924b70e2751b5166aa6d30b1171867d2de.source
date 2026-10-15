package com.zhixing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.Result;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.entity.QaSession;
import com.zhixing.service.QaService;
import com.zhixing.service.SourceRef;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 问答线 HTTP 入口。/api/qa/stream 返回 SseEmitter，事件协议见概设 §3.4；
 * 失败语义 DL-3：HTTP 恒 200 + 业务码，SSE 场景天然适用。
 */
@Slf4j
@Validated
@RestController
public class QaController {

    private final QaService qaService;

    private final ObjectMapper objectMapper;

    public QaController(QaService qaService, ObjectMapper objectMapper) {
        this.qaService = qaService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/qa/stream")
    public SseEmitter stream(@Valid @RequestBody QaStreamRequest request) {
        log.info("qa stream: kbId={}, sessionId={}, question={}",
                request.getKbId(), request.getSessionId(), request.getQuestion());
        // 超时/生命周期回调/终态成对全部由 QaService + SseEmitterSink 管理
        return qaService.streamQa(request);
    }

    public record SessionView(Long id, String title, java.time.LocalDateTime createdAt) {
    }

    @GetMapping("/api/sessions")
    public Result<List<SessionView>> sessions() {
        return Result.ok(qaService.listSessions().stream()
                .map(s -> new SessionView(s.getId(), s.getTitle(), s.getCreatedAt()))
                .toList());
    }

    public record MessageView(Long id, String role, String content, String engine, List<SourceRef> sources,
                              Integer promptTokens, Integer completionTokens, Long costMs,
                              java.time.LocalDateTime createdAt) {
    }

    @GetMapping("/api/sessions/{id}/messages")
    public Result<List<MessageView>> messages(@PathVariable long id) {
        return Result.ok(qaService.listMessages(id).stream().map(this::toView).toList());
    }

    private MessageView toView(QaMessage m) {
        return new MessageView(m.getId(), m.getRole(), m.getContent(), m.getEngine(),
                parseSources(m.getSources()), m.getPromptTokens(), m.getCompletionTokens(),
                m.getCostMs(), m.getCreatedAt());
    }

    private List<SourceRef> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SourceRef.class));
        } catch (Exception e) {
            log.warn("sources 反序列化失败: {}", e.getMessage());
            return List.of();
        }
    }
}
