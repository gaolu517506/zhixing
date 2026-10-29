package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.service.AdminUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理 HTTP 入口（02-技术方案 §B7，F7）。系统 ADMIN 门禁在 AdminUserService 入口
 * （controller 层零权限判断）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Result<List<AdminUserService.UserView>> list() {
        return Result.ok(userService.list());
    }

    @PostMapping
    public Result<AdminUserService.UserView> create(@Valid @RequestBody CreateRequest request) {
        return Result.ok(userService.create(new AdminUserService.CreateUserRequest(
                request.username(), request.password(), request.systemRole())));
    }

    @PatchMapping("/{id}")
    public Result<AdminUserService.UserView> patch(@PathVariable long id,
                                                   @Valid @RequestBody PatchRequest request) {
        log.info("admin user patch: id={}, action={}", id, request.action());
        return Result.ok(userService.patch(id,
                new AdminUserService.PatchUserRequest(request.action(), request.newPassword())));
    }

    public record CreateRequest(@NotBlank(message = "username 不能为空") @Size(max = 64) String username,
                                @NotBlank(message = "password 不能为空") String password,
                                String systemRole) {
    }

    public record PatchRequest(@NotBlank(message = "action 不能为空") String action,
                               String newPassword) {
    }
}
