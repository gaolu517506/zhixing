package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.IntentRoute;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.IntentRouteMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 意图路由规则管理与试测（02-技术方案 §B10，F11；契约 contracts/m3-org-intent-contracts.ts 线 G 段）。
 * 仅系统 ADMIN（门禁在 service 入口）；规则 CRUD 为配置类操作不落审计（DL-30 ④裁决）。
 * 三级路由逻辑在 {@link IntentRouter}（纯逻辑可测），本类负责规则持久化。
 */
@Slf4j
@Service
public class IntentRouteService {

    private final IntentRouteMapper routeMapper;

    private final KbKnowledgeBaseMapper kbMapper;

    private final ObjectMapper objectMapper;

    public IntentRouteService(IntentRouteMapper routeMapper, KbKnowledgeBaseMapper kbMapper,
                              ObjectMapper objectMapper) {
        this.routeMapper = routeMapper;
        this.kbMapper = kbMapper;
        this.objectMapper = objectMapper;
    }

    // ===== 视图/请求 =====

    public record RouteView(Long id, String name, List<String> keywords, List<String> examples,
                            List<Long> targetKbIds, Boolean enabled, LocalDateTime createdAt) {

        static RouteView of(IntentRoute entity) {
            return new RouteView(entity.getId(), entity.getName(),
                    parseList(entity.getKeywords()), parseList(entity.getExamples()),
                    parseLongs(entity.getTargetKbIds()), entity.getEnabled(), entity.getCreatedAt());
        }
    }

    public record UpsertRequest(String name, List<String> keywords, List<String> examples,
                                List<Long> targetKbIds, Boolean enabled) {
    }

    /** 试测结果（契约 RouteTestResult）。matched：KEYWORD / LLM / FALLBACK。 */
    public record RouteTestResult(String matched, Long routeId, String routeName,
                                  Integer score, List<Long> routedKbIds) {
    }

    // ===== CRUD =====

    public List<RouteView> list() {
        requireSystemAdmin();
        return routeMapper.selectList(new LambdaQueryWrapper<IntentRoute>()
                        .orderByDesc(IntentRoute::getCreatedAt)
                        .orderByDesc(IntentRoute::getId))
                .stream().map(RouteView::of).toList();
    }

    /** 试测端点门禁入口（controller 的 test 直调 router 须经此处校验——矩阵测试抓获的绕行缺陷修复点）。 */
    public void requireManagePermission() {
        requireSystemAdmin();
    }

    public RouteView create(UpsertRequest request) {
        requireSystemAdmin();
        validate(request);
        String name = request.name().trim();
        if (routeMapper.selectCount(new LambdaQueryWrapper<IntentRoute>()
                .eq(IntentRoute::getName, name)) > 0) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "意图名已存在：" + name);
        }
        IntentRoute entity = new IntentRoute();
        entity.setId(IdUtil.getSnowflakeNextId());
        copyTo(request, entity);
        entity.setCreatedAt(LocalDateTime.now());
        routeMapper.insert(entity);
        log.info("管理员 {} 新建意图路由 {}（{}）", AuthContext.current().username(), name, entity.getId());
        return RouteView.of(entity);
    }

    public RouteView update(long id, UpsertRequest request) {
        requireSystemAdmin();
        IntentRoute entity = requireRoute(id);
        validate(request);
        String name = request.name().trim();
        IntentRoute dup = routeMapper.selectOne(new LambdaQueryWrapper<IntentRoute>()
                .eq(IntentRoute::getName, name));
        if (dup != null && dup.getId() != id) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "意图名已存在：" + name);
        }
        copyTo(request, entity);
        routeMapper.updateById(entity);
        return RouteView.of(entity);
    }

    public void delete(long id) {
        requireSystemAdmin();
        requireRoute(id);
        routeMapper.deleteById(id);
    }

    // ===== 内部 =====

    private List<Long> intersect(IntentRoute rule, List<IntentRoute> all) {
        // 试测场景 accessible = 全部启用规则目标并集的判定域；此处返回规则目标（去重）
        return parseLongsSafe(rule.getTargetKbIds()).stream().distinct().toList();
    }

    private RouteTestResult toTestResult(String matched, IntentRoute rule, Integer score, List<Long> targetKbIds) {
        return new RouteTestResult(matched, rule.getId(), rule.getName(), score, targetKbIds);
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

    private void validate(UpsertRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "意图名不能为空且不超过 64 字符");
        }
        List<String> keywords = request.keywords() == null ? List.of() : request.keywords();
        if (keywords.isEmpty() || keywords.stream().anyMatch(k -> k == null || k.isBlank())) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "keywords 不能为空且不得含空白项");
        }
        if (request.targetKbIds() == null || request.targetKbIds().isEmpty()) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "targetKbIds 不能为空");
        }
        for (Long kbId : request.targetKbIds()) {
            if (kbId == null || kbMapper.selectById(kbId) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "目标知识库不存在：" + kbId);
            }
        }
    }

    private void copyTo(UpsertRequest request, IntentRoute entity) {
        entity.setName(request.name().trim());
        entity.setKeywords(writeJson(request.keywords() == null ? List.of() : request.keywords()));
        entity.setExamples(writeJson(request.examples() == null ? List.of() : request.examples()));
        entity.setTargetKbIds(writeJson(request.targetKbIds()));
        entity.setEnabled(request.enabled() == null || request.enabled());
    }

    private IntentRoute requireRoute(long id) {
        IntentRoute entity = routeMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "意图路由不存在：" + id);
        }
        return entity;
    }

    private void requireSystemAdmin() {
        CurrentUser caller = AuthContext.current();
        if (!caller.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员可管理意图路由");
        }
    }

    private String writeJson(List<?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "规则序列化失败");
        }
    }

    static List<String> parseList(String json) {
        return parseListSafe(json);
    }

    static List<Long> parseLongs(String json) {
        return parseLongsSafe(json);
    }

    private static List<String> parseListSafe(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Long> parseLongsSafe(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
