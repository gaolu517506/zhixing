package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.service.IntentRouteService;
import com.zhixing.service.IntentRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 意图路由管理 HTTP 入口（02-技术方案 §B10，F11；契约 contracts/m3-org-intent-contracts.ts 线 G 段）：
 * 规则 CRUD + 试测面板干跑。仅系统 ADMIN（门禁在 service 入口，controller 零判断）；
 * 规则 CRUD 为配置类操作不落审计（DL-30 ④裁决）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/routes")
public class IntentRouteController {

    private final IntentRouteService routeService;

    private final IntentRouter intentRouter;

    public IntentRouteController(IntentRouteService routeService, IntentRouter intentRouter) {
        this.routeService = routeService;
        this.intentRouter = intentRouter;
    }

    @GetMapping
    public Result<List<IntentRouteService.RouteView>> list() {
        return Result.ok(routeService.list());
    }

    @PostMapping
    public Result<IntentRouteService.RouteView> create(@RequestBody IntentRouteService.UpsertRequest request) {
        log.info("intent route create: name={}", request.name());
        return Result.ok(routeService.create(request));
    }

    @PutMapping("/{id}")
    public Result<IntentRouteService.RouteView> update(@PathVariable long id,
                                                       @RequestBody IntentRouteService.UpsertRequest request) {
        log.info("intent route update: id={}, name={}", id, request.name());
        return Result.ok(routeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        log.info("intent route delete: id={}", id);
        routeService.delete(id);
        return Result.ok(null);
    }

    /** 试测面板（PRD 用户故事 43）：干跑返回命中意图/得分/目标库，不产生真实检索。 */
    @PostMapping("/test")
    public Result<RouteTestResponse> test(@RequestBody Map<String, String> request) {
        routeService.requireManagePermission(); // 门禁（矩阵测试抓获 test 直调 router 的绕行缺陷，已修复）
        String query = request.getOrDefault("query", "");
        log.info("intent route test: query={}", query);
        IntentRouter.RouteDecision decision = intentRouter.test(query);
        return Result.ok(new RouteTestResponse(decision.matched(), decision.routeId(),
                decision.routeName(), decision.score(), decision.routedKbIds()));
    }

    /** 试测响应（契约 RouteTestResult；routeId/routeName/score FALLBACK 时为 null）。 */
    public record RouteTestResponse(String matched, Long routeId, String routeName,
                                    Integer score, List<Long> routedKbIds) {
    }
}
