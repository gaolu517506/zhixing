package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.dao.entity.ApiToken;
import com.zhixing.service.ApiTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 开放 API token 管理端点（v2.0 F5，02-技术方案 §B5）：发放 / 吊销 / 列表（脱哈希）。
 * 权限：service 入口 requireAdmin（系统 ADMIN）；路径级门控待线 A AuthFilter 合入后兜底。
 */
@RestController
@RequestMapping("/api/admin/tokens")
public class ApiTokenAdminController {

    private final ApiTokenService tokenService;

    public ApiTokenAdminController(ApiTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping
    public Result<TokenIssuedResponse> issue(@Valid @RequestBody CreateTokenRequest request) {
        ApiTokenService.IssuedToken issued = tokenService.issue(request.name(), request.ownerUserId(),
                request.dailyQuota(), request.expiresAt());
        return Result.ok(new TokenIssuedResponse(issued.id(), issued.name(), issued.plaintextToken(),
                issued.ownerUserId(), issued.dailyQuota(), issued.expiresAt()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        tokenService.revoke(id);
        return Result.ok(null);
    }

    @GetMapping
    public Result<List<TokenItemResponse>> list() {
        return Result.ok(tokenService.list().stream().map(TokenItemResponse::of).toList());
    }

    public record CreateTokenRequest(
            @NotBlank(message = "name 不能为空") @Size(max = 64, message = "name 最长 64 字符") String name,
            Long ownerUserId,
            @Positive(message = "dailyQuota 必须为正数") Integer dailyQuota,
            LocalDateTime expiresAt) {
    }

    /** token 明文仅在此响应出现一次（不落库、不再次查询）。 */
    public record TokenIssuedResponse(Long id, String name, String token, Long ownerUserId,
                                      Integer dailyQuota, LocalDateTime expiresAt) {
    }

    /** 列表脱哈希：不携带 tokenHash 字段。 */
    public record TokenItemResponse(Long id, String name, Long ownerUserId, String status,
                                    Integer dailyQuota, Integer usedToday, LocalDate usedDate,
                                    LocalDateTime expiresAt, LocalDateTime lastUsedAt, LocalDateTime createdAt) {

        static TokenItemResponse of(ApiToken token) {
            return new TokenItemResponse(token.getId(), token.getName(), token.getOwnerUserId(),
                    token.getStatus(), token.getDailyQuota(), token.getUsedToday(), token.getUsedDate(),
                    token.getExpiresAt(), token.getLastUsedAt(), token.getCreatedAt());
        }
    }
}
