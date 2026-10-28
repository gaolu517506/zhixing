package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.service.AuditQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 审计查询 HTTP 入口（02-技术方案 §B3）：条件查询 + CSV 流式导出。
 * 系统管理员门禁在 AuditQueryService 入口（controller 层零权限判断）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuditController {

    private final AuditQueryService auditQueryService;

    public AdminAuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    /** from/to 为 ISO 本地日期时间（如 2026-10-28T00:00:00），闭区间。 */
    @GetMapping("/audit")
    public Result<AuditQueryService.AuditPage> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(auditQueryService.page(
                new AuditQueryService.AuditQuery(from, to, actorId, action), page, size));
    }

    @GetMapping("/audit/export")
    public void export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=audit.csv");
        PrintWriter writer = response.getWriter();
        auditQueryService.exportCsv(new AuditQueryService.AuditQuery(from, to, actorId, action), writer);
        writer.flush();
    }
}
