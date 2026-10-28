package com.zhixing.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未认证 401 全端点矩阵（02 §B1 测试点）：白名单之外所有端点在 AuthFilter 即 401+A0005，
 * 与 handler 是否存在无关；白名单（/api/health、/api/auth/setup-status、login、setup*、
 * actuator health）可达；CORS 预检不拦。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFilterMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

    private void expectUnauthorized(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        mockMvc.perform(b).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0005"));
    }

    @Test
    void 未认证_kb面_全部401() throws Exception {
        expectUnauthorized(get("/api/kb"));
        expectUnauthorized(post("/api/kb").content("{}"));
        expectUnauthorized(delete("/api/kb/123"));
        expectUnauthorized(get("/api/kb/123/documents"));
        expectUnauthorized(get("/api/documents/123"));
        expectUnauthorized(delete("/api/documents/123"));
        expectUnauthorized(multipart("/api/kb/123/documents"));
    }

    @Test
    void 未认证_qa会话面_全部401() throws Exception {
        expectUnauthorized(post("/api/qa/stream").content("{}"));
        expectUnauthorized(get("/api/sessions"));
        expectUnauthorized(get("/api/sessions/123/messages"));
    }

    @Test
    void 未认证_agent面_全部401() throws Exception {
        expectUnauthorized(post("/api/agent/run").content("{}"));
        expectUnauthorized(post("/api/agent/runs/123/confirm").content("{}"));
        expectUnauthorized(post("/api/agent/runs/123/cancel"));
        expectUnauthorized(get("/api/agent/runs"));
    }

    @Test
    void 未认证_trace与管理面_全部401() throws Exception {
        expectUnauthorized(get("/api/trace/runs"));
        expectUnauthorized(get("/api/trace/runs/123"));
        expectUnauthorized(get("/api/auth/me"));
        expectUnauthorized(post("/api/auth/logout"));
        expectUnauthorized(get("/api/admin/audit"));
        expectUnauthorized(get("/api/admin/audit/export"));
        expectUnauthorized(get("/api/dev/echo").param("msg", "x"));
    }

    @Test
    void 白名单路径_未认证可达() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        mockMvc.perform(get("/api/auth/setup-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        // login 未认证可达（错误凭据 → 401，但那是业务拒绝而非过滤层拦截）
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"matrix-nobody\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        // actuator 依赖未引入：白名单放行后落入 404 分支——关键是不过滤层 401
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0004"));
    }

    @Test
    void 无效token与格式非法头_401() throws Exception {
        expectUnauthorized(get("/api/kb").header("Authorization", "Bearer totally-invalid-token"));
        expectUnauthorized(get("/api/kb").header("Authorization", "Basic dXNlcjpwYXNz"));
        expectUnauthorized(get("/api/kb").header("Authorization", "Bearer "));
    }

    @Test
    void cors预检不拦() throws Exception {
        mockMvc.perform(options("/api/kb")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(ResultMatcher.matchAll(status().isOk(),
                        content().string(org.hamcrest.Matchers.emptyOrNullString())));
    }
}
