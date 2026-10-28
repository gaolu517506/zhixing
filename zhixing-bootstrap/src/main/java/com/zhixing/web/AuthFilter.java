package com.zhixing.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.AuthTokenException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.IErrorCode;
import com.zhixing.common.Result;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.service.AuthService;
import com.zhixing.service.TokenResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 认证过滤器（02-技术方案 §B1 校验链）：Bearer 明文 token → 会话校验（AuthService）→
 * 未命中且存在线 B 的 TokenResolver 时走开放 API token → AuthContext 注入，finally 清除
 * （线程池复用下漏清会把身份泄漏给下一个请求，契约级要求）。
 *
 * <p>白名单仅：/api/health、/api/auth/login、/api/auth/setup*（首启向导）、actuator health；
 * CORS 预检（OPTIONS）不拦。白名单之外的任何请求无有效凭据一律 HTTP 401 + A0005
 * （v2.0 认证段显式引入真实状态码，Result 业务码协议不变；A0007 配额超限按 429 翻译）。
 *
 * <p>token 主语判定顺序（§B5）：先查 WEB 会话未命中 → 再查 api_token（线 B 实现落位后生效，
 * 未落位时 ObjectProvider 为空、API token 一律 401）。
 *
 * <p>ERROR/ASYNC dispatch 不过滤：异常转发与 SSE 异步完成不重复鉴权（上下文已清除，
 * 二次过滤会把已完成鉴权的请求误判 401）。
 */
@Slf4j
@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 精确白名单路径；/api/auth/setup* 前缀（setup / setup-status）单列。 */
    private static final List<String> WHITELIST = List.of(
            "/api/health",
            "/api/auth/login",
            "/actuator/health");

    private static final String SETUP_PREFIX = "/api/auth/setup";

    private final AuthService authService;

    private final ObjectProvider<TokenResolver> tokenResolverProvider;

    private final ObjectMapper objectMapper;

    public AuthFilter(AuthService authService, ObjectProvider<TokenResolver> tokenResolverProvider,
                      ObjectMapper objectMapper) {
        this.authService = authService;
        this.tokenResolverProvider = tokenResolverProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return WHITELIST.contains(path) || path.startsWith(SETUP_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CurrentUser user;
        try {
            user = resolve(request, response);
        } catch (Exception e) {
            // 校验链自身的故障（如 DB 不可用）：500 如实上报，不伪装成 401 让用户反复重试密码
            log.error("认证校验链异常 path={}", request.getRequestURI(), e);
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ErrorCode.SERVER_ERROR);
            }
            return;
        }
        if (user == null) {
            return; // resolve 已写 401/429 响应
        }
        try {
            AuthContext.set(user);
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    /**
     * 凭据解析：命中返回主体；未命中写 401 返回 null；命中但配额超限（线 B）写 429 返回 null。
     */
    private CurrentUser resolve(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String header = request.getHeader("Authorization");
        String rawToken = header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim()
                : null;
        if (rawToken == null || rawToken.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return null;
        }
        CurrentUser user = authService.validate(rawToken);
        if (user == null) {
            TokenResolver resolver = tokenResolverProvider.getIfAvailable();
            if (resolver != null) {
                try {
                    user = resolver.resolve(rawToken);
                } catch (AuthTokenException e) {
                    // 命中但日配额用尽（A0007）：不吞真实 429 语义（§B5）
                    log.warn("API token 配额超限 path={}", request.getRequestURI());
                    writeError(response, 429, e.errorCode());
                    return null;
                }
            }
        }
        if (user == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return null;
        }
        return user;
    }

    private void writeError(HttpServletResponse response, int status, IErrorCode errorCode) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(errorCode)));
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
