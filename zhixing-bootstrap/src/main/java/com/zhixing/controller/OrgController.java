package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.service.OrgService;
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

/**
 * 组织管理 HTTP 入口（02-技术方案 §B9，F10；契约 contracts/m3-org-intent-contracts.ts 线 F 段）：
 * 组织 CRUD + 成员全量替换 + 库归属全量替换。系统 ADMIN ∪ 本组织管理员门禁在
 * OrgService 入口（controller 零判断）；变更落 ORG_CHANGE 审计。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/orgs")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping
    public Result<List<OrgService.OrgSummaryView>> list() {
        return Result.ok(orgService.list());
    }

    @PostMapping
    public Result<OrgService.OrgView> create(@RequestBody OrgService.CreateOrgRequest request) {
        log.info("org create: name={}", request.name());
        return Result.ok(orgService.create(request));
    }

    @DeleteMapping("/{orgId}")
    public Result<Void> delete(@PathVariable long orgId) {
        log.info("org delete: orgId={}", orgId);
        orgService.delete(orgId);
        return Result.ok(null);
    }

    @GetMapping("/{orgId}/members")
    public Result<List<OrgService.OrgMemberView>> members(@PathVariable long orgId) {
        return Result.ok(orgService.members(orgId));
    }

    /** PUT 全量替换语义：以请求集为准做差量增删改（差量落一条 ORG_CHANGE）。 */
    @PutMapping("/{orgId}/members")
    public Result<List<OrgService.OrgMemberView>> putMembers(@PathVariable long orgId,
                                                             @RequestBody OrgService.PutMembersRequest request) {
        log.info("org members put: orgId={}, members={}", orgId,
                request.members() == null ? 0 : request.members().size());
        return Result.ok(orgService.putMembers(orgId, request));
    }

    @GetMapping("/{orgId}/kbs")
    public Result<List<OrgService.KbOrgView>> listKbs(@PathVariable long orgId) {
        return Result.ok(orgService.listKbs(orgId));
    }

    @PutMapping("/{orgId}/kbs")
    public Result<Void> putKbs(@PathVariable long orgId, @RequestBody OrgService.PutKbsRequest request) {
        log.info("org kbs put: orgId={}, kbs={}", orgId,
                request.kbIds() == null ? 0 : request.kbIds().size());
        orgService.putKbs(orgId, request);
        return Result.ok(null);
    }
}
