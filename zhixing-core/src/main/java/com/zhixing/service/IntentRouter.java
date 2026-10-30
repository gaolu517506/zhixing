package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.RouteProperties;
import com.zhixing.dao.entity.IntentRoute;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.IntentRouteMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.dto.ChatMessage;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图路由器（02-技术方案 §B10，F11）：三级路由，纯 Java 可测——
 * ①关键词级：query 标准化（小写/去首尾空白）任一关键词命中 → 候选按命中词数排序取首；
 * ②未命中 → LLM 分类（候选意图 + examples few-shot，temperature=0，返回意图标签；
 * 失败/空回复/「无」进回落）；
 * ③回落 = accessibleKbIds 全集（**路由是增益不是依赖，永不失败**）。
 *
 * <p>输出 routedKbIds = 规则目标 ∩ accessibleKbIds（D19：路由结果必须再过权限）；
 * 交集为空视同未命中 → 下一级/回落。LLM 仅在 {@link RouteProperties#isLlmEnabled()} 时尝试。
 * 启动问答链路的路由开关（zhixing.route.enabled）由 QaService 判定。
 */
@Slf4j
@Component
public class IntentRouter {

    /** 路由判定结果（契约 RouteTestResult 同构；routedKbIds 已过权限交集，FALLBACK 时 = accessible 全集）。 */
    public record RouteDecision(String matched, Long routeId, String routeName,
                                Integer score, List<Long> routedKbIds) {
    }

    private final IntentRouteMapper routeMapper;

    private final KbKnowledgeBaseMapper kbMapper;

    private final ChatClient chatClient;

    private final RouteProperties props;

    private final ObjectMapper objectMapper;

    public IntentRouter(IntentRouteMapper routeMapper, KbKnowledgeBaseMapper kbMapper,
                        ChatClient chatClient, RouteProperties props, ObjectMapper objectMapper) {
        this.routeMapper = routeMapper;
        this.kbMapper = kbMapper;
        this.chatClient = chatClient;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 三级路由（问答链路入口）。任一级产出「规则目标 ∩ accessible 非空」即定；
     * 否则继续下一级；全部未命中 → FALLBACK(accessible 全集)。
     */
    public RouteDecision route(String query, List<Long> accessibleKbIds) {
        List<Long> accessible = accessibleKbIds == null ? List.of() : accessibleKbIds;
        if (!props.isEnabled() || accessible.isEmpty()) {
            return fallback(accessible);
        }
        String normalized = query == null ? "" : query.trim().toLowerCase();
        List<IntentRoute> rules = routeMapper.selectList(new LambdaQueryWrapper<IntentRoute>()
                .eq(IntentRoute::getEnabled, true));

        // ① 关键词级
        IntentRoute best = null;
        int bestScore = 0;
        for (IntentRoute rule : rules) {
            int hits = keywordHits(normalized, parseList(rule.getKeywords()));
            if (hits > bestScore) {
                best = rule;
                bestScore = hits;
            }
        }
        if (best != null) {
            RouteDecision decision = decide("KEYWORD", best, bestScore, accessible);
            if (decision != null) {
                return decision;
            }
        }

        // ② LLM 分类（低温 + few-shot；失败/无法判断进回落）
        if (props.isLlmEnabled()) {
            IntentRoute llmMatch = classifyByLlm(query, rules);
            if (llmMatch != null) {
                RouteDecision decision = decide("LLM", llmMatch, null, accessible);
                if (decision != null) {
                    return decision;
                }
            }
        }

        // ③ 回落：全部有权限库（永不失败）
        return fallback(accessible);
    }

    /** 试测面板干跑：accessible = 库内现存全部库（不做权限裁剪预览；悬空目标 ID 过滤）。 */
    public RouteDecision test(String query) {
        List<Long> allKbIds = kbMapper.selectList(null).stream()
                .map(KbKnowledgeBase::getId).toList();
        RouteDecision decision = route(query, allKbIds);
        List<Long> existing = decision.routedKbIds().stream()
                .filter(id -> kbMapper.selectById(id) != null).toList();
        return new RouteDecision(decision.matched(), decision.routeId(), decision.routeName(),
                decision.score(), existing);
    }

    // ===== 内部 =====

    /** 规则目标 ∩ accessible；交集为空返回 null（视为未命中，调用方继续下一级）。 */
    private RouteDecision decide(String matched, IntentRoute rule, Integer score, List<Long> accessible) {
        List<Long> routed = parseLongs(rule.getTargetKbIds()).stream()
                .distinct().filter(accessible::contains).toList();
        if (routed.isEmpty()) {
            return null;
        }
        return new RouteDecision(matched, rule.getId(), rule.getName(), score, routed);
    }

    private RouteDecision fallback(List<Long> accessible) {
        return new RouteDecision("FALLBACK", null, null, null, new ArrayList<>(accessible));
    }

    private IntentRoute classifyByLlm(String query, List<IntentRoute> rules) {
        if (rules.isEmpty() || query == null || query.isBlank()) {
            return null;
        }
        StringBuilder prompt = new StringBuilder("你是意图分类器。候选意图：\n");
        for (IntentRoute rule : rules) {
            prompt.append("- ").append(rule.getName());
            List<String> examples = parseList(rule.getExamples());
            if (!examples.isEmpty()) {
                prompt.append("（示例：").append(String.join("；", examples)).append("）");
            }
            prompt.append("\n");
        }
        prompt.append("问题：").append(query.trim())
                .append("\n只回复最匹配的意图名，无法判断回复「无」。");

        try {
            ChatResponse response = chatClient.call(ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt.toString())))
                    .temperature(0.0)
                    .maxTokens(32)
                    .build());
            String reply = response != null ? response.content() : null;
            if (reply == null || reply.isBlank()) {
                return null;
            }
            String label = reply.trim();
            if (label.contains("无")) {
                return null;
            }
            return rules.stream()
                    .filter(r -> label.contains(r.getName()) || r.getName().contains(label))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("意图 LLM 分类失败（回落）：{}", e.getMessage());
            return null;
        }
    }

    private int keywordHits(String normalizedQuery, List<String> keywords) {
        int hits = 0;
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && normalizedQuery.contains(keyword.trim().toLowerCase())) {
                hits += 1;
            }
        }
        return hits;
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Long> parseLongs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
