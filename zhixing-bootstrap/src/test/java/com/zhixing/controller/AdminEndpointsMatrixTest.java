package com.zhixing.controller;

import com.zhixing.TestAuth;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.mapper.AuthSessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 管理后台权限矩阵（02 §B7 测试点，任务书验收①）：admin 四组端点非系统 ADMIN 一律 403/A0006；
 * 系统管理员可达（200 或业务成功码）；未认证 401（线 A AuthFilter 兜底）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminEndpointsMatrixTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthUserMapper authUserMapper;

    @Autowired
    private AuthSessionMapper authSessionMapper;

    private TestAuth.SeededUser admin;

    private TestAuth.SeededUser user;

    private final List<TestAuth.SeededUser> seeded = new ArrayList<>();

    @BeforeEach
    void seed() {
        String tag = String.valueOf(System.nanoTime());
        admin = TestAuth.seed(authUserMapper, authSessionMapper, "m2-admin-" + tag, "ADMIN");
        user = TestAuth.seed(authUserMapper, authSessionMapper, "m2-user-" + tag, "USER");
        seeded.add(admin);
        seeded.add(user);
    }

    @AfterEach
    void cleanup() {
        for (TestAuth.SeededUser u : seeded) {
            TestAuth.cleanup(authUserMapper, authSessionMapper, u);
        }
        seeded.clear();
    }

    private void expect403AsUser(MockHttpServletRequestBuilder builder) throws Exception {
        mockMvc.perform(builder.header("Authorization", TestAuth.bearer(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }

    @Test
    void admin端点_普通用户全403() throws Exception {
        // 用户管理
        expect403AsUser(get("/api/admin/users"));
        expect403AsUser(post("/api/admin/users").contentType("application/json")
                .content("{\"username\":\"x\",\"password\":\"pass-123\"}"));
        expect403AsUser(patch("/api/admin/users/1").contentType("application/json")
                .content("{\"action\":\"DISABLE\"}"));
        // 库权限
        expect403AsUser(get("/api/admin/kbs/9/members"));
        expect403AsUser(put("/api/admin/kbs/9/members").contentType("application/json")
                .content("{\"members\":[]}"));
        expect403AsUser(patch("/api/admin/kbs/9").contentType("application/json")
                .content("{\"visibility\":\"ALL\"}"));
        // 健康
        expect403AsUser(get("/api/admin/health"));
        // 审计（线 A 端点，随本线一起回归）
        expect403AsUser(get("/api/admin/audit"));
        // 全库列表（联调对齐线新增）
        expect403AsUser(get("/api/admin/kbs"));
    }

    @Test
    void admin端点_未认证401() throws Exception {
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/health")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/kbs/9/members")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/kbs")).andExpect(status().isUnauthorized());
    }

    @Test
    void admin端点_系统管理员可达() throws Exception {
        String bearer = TestAuth.bearer(admin);
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        mockMvc.perform(get("/api/admin/health").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.backend").value("UP"))
                .andExpect(jsonPath("$.data.db.up").value(true))
                .andExpect(jsonPath("$.data.storage.type").value("local"));
        mockMvc.perform(get("/api/admin/kbs").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray());
        // 不存在的库：系统管理员过门禁后落 K1001（业务码而非 403）
        mockMvc.perform(get("/api/admin/kbs/999999999/members").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("K1001"));
    }
}
