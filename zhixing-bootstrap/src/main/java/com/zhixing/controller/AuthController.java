package com.zhixing.controller;

import com.zhixing.common.Result;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.service.AuthService;
import com.zhixing.service.DemoSeedService;
import com.zhixing.service.OrgService;
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
 *
 * <p>首启向导扩展（§B8，F8 后端半，本线交付）：setup-status 增 seedAvailable
 * （演示种子能力诚实呈现）；setup 请求可选带 seed=true——建号成功后播种演示库。
 * 认证主链路（AuthService）为线 A 交付物零改动，向导编排收在本 controller。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    private final DemoSeedService demoSeedService;

    private final OrgService orgService;

    public AuthController(AuthService authService, DemoSeedService demoSeedService, OrgService orgService) {
        this.authService = authService;
        this.demoSeedService = demoSeedService;
        this.orgService = orgService;
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

    /** me：orgs additive（F10 线 F——顶栏组织徽标与 /kb 筛选数据源，契约 MeOrgsExtension）。 */
    @GetMapping("/me")
    public Result<AuthService.UserView> me() {
        var user = AuthContext.current();
        return Result.ok(new AuthService.UserView(user.id(), user.username(), user.systemRole(), null)
                .withOrgs(orgService.membershipsOf(user.id())));
    }

    /** 首启向导第一步：needSetup=auth_user 空表；seedAvailable=演示种子可用（§B8）。 */
    @GetMapping("/setup-status")
    public Result<SetupStatus> setupStatus() {
        return Result.ok(new SetupStatus(authService.needSetup(), demoSeedService.seedAvailable()));
    }

    /**
     * 仅空表可用：创建首个 ADMIN 并直接签发会话（向导免二次登录）；
     * seed=true 且种子可用时同步建演示库（文档入库在流水线异步完成）。
     */
    @PostMapping("/setup")
    public Result<SetupResult> setup(@Valid @RequestBody SetupWizardRequest request) {
        AuthService.LoginResult login = authService.setup(
                new AuthService.SetupRequest(request.username(), request.password()));
        Long seededKbId = null;
        if (Boolean.TRUE.equals(request.seed()) && demoSeedService.seedAvailable()) {
            CurrentUser owner = new CurrentUser(login.user().id(), login.user().username(),
                    login.user().systemRole(), AuthChannel.WEB);
            seededKbId = demoSeedService.seedLibrary(owner);
            log.info("首启向导完成（含演示库）admin={} kbId={}", login.user().username(), seededKbId);
        }
        return Result.ok(new SetupResult(login.token(), login.user(), seededKbId));
    }

    public record SetupStatus(boolean needSetup, boolean seedAvailable) {
    }

    /** 向导请求：username/password 为线 A setup 契约，seed 为本线可选扩展（缺省 false）。 */
    public record SetupWizardRequest(String username, String password, Boolean seed) {
    }

    /** 向导响应：seededKbId 仅在演示库实际播种时非空（诚实区分「请求了」与「种上了」）。 */
    public record SetupResult(String token, AuthService.UserView user, Long seededKbId) {
    }

    private static String bearerTokenOf(String authorization) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return authorization == null ? "" : authorization.trim();
    }
}
