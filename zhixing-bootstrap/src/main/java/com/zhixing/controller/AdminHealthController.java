package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.service.AdminHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统健康 HTTP 入口（02-技术方案 §B7，F7）：聚合读现态，不主动 ping 模型供应商。
 * 系统 ADMIN 门禁在 AdminHealthService 入口。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminHealthController {

    private final AdminHealthService healthService;

    public AdminHealthController(AdminHealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public Result<AdminHealthService.HealthView> health() {
        return Result.ok(healthService.health());
    }
}
