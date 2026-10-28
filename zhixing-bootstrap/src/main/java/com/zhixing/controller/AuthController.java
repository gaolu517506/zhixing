package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.service.AuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 HTTP 入口（02-技术方案 §B1）：login/logout/me + 首启 setup-status/setup。
 * login 与 setup* 在 AuthFilter 白名单（未认证可达）；logout/me 必须带有效 Bearer。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<AuthService.LoginResult> login(@Valid @RequestBody AuthService.LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /** 登出：删除服务端会话行（opaque token 即刻吊销）。 */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(bearerTokenOf(authorization));
        return Result.ok(null);
    }

    @GetMapping("/me")
    public Result<AuthService.UserView> me() {
        var user = AuthContext.current();
        return Result.ok(new AuthService.UserView(user.id(), user.username(), user.systemRole()));
    }

    /** 首启向导第一步：仅 auth_user 空表时 needSetup=true（02 §B1/B8）。 */
    @GetMapping("/setup-status")
    public Result<SetupStatus> setupStatus() {
        return Result.ok(new SetupStatus(authService.needSetup()));
    }

    /** 仅空表可用：创建首个 ADMIN 并直接签发会话（向导免二次登录）。 */
    @PostMapping("/setup")
    public Result<AuthService.LoginResult> setup(@Valid @RequestBody AuthService.SetupRequest request) {
        return Result.ok(authService.setup(request));
    }

    public record SetupStatus(boolean needSetup) {
    }

    private static String bearerTokenOf(String authorization) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return authorization == null ? "" : authorization.trim();
    }
}
