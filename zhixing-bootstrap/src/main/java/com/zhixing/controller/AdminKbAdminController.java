package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.service.AdminKbAdminService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库权限管理 HTTP 入口（02-技术方案 §B7，F7）：成员列表/全量替换 + visibility。
 * 系统 ADMIN 门禁在 AdminKbAdminService 入口；变更落 PERMISSION_CHANGE 审计。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/kbs/{kbId}")
public class AdminKbAdminController {

    private final AdminKbAdminService kbAdminService;

    public AdminKbAdminController(AdminKbAdminService kbAdminService) {
        this.kbAdminService = kbAdminService;
    }

    @GetMapping("/members")
    public Result<AdminKbAdminService.KbPermissionView> members(@PathVariable long kbId) {
        return Result.ok(kbAdminService.members(kbId));
    }

    /** PUT 全量替换语义：以请求集为准做差量增删改（增量变更落一条 PERMISSION_CHANGE）。 */
    @PutMapping("/members")
    public Result<AdminKbAdminService.KbPermissionView> putMembers(@PathVariable long kbId,
                                                                   @Valid @RequestBody PutMembersRequest request) {
        log.info("admin kb members put: kbId={}, members={}", kbId,
                request.members() == null ? 0 : request.members().size());
        return Result.ok(kbAdminService.putMembers(kbId,
                new AdminKbAdminService.PutMembersRequest(request.members())));
    }

    @PatchMapping
    public Result<AdminKbAdminService.KbPermissionView> patchKb(@PathVariable long kbId,
                                                                @Valid @RequestBody PatchKbRequest request) {
        log.info("admin kb patch: kbId={}, visibility={}", kbId, request.visibility());
        return Result.ok(kbAdminService.patchKb(kbId,
                new AdminKbAdminService.PatchKbRequest(request.visibility())));
    }

    public record PutMembersRequest(java.util.List<AdminKbAdminService.MemberUpsert> members) {
    }

    public record PatchKbRequest(String visibility) {
    }
}
