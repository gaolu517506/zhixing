package com.zhixing.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未知路径 404 → A 段（评审 S3，任务书 v1.1-D ①）：NoResourceFound/NoHandlerFound 不再落
 * 兜底 B0001「服务内部错误」，归 A0004「资源不存在」。DL-3：HTTP 恒 200，语义在业务码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnknownPathNotFoundTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.zhixing.dao.mapper.AuthUserMapper authUserMapper;

    @Autowired
    private com.zhixing.dao.mapper.AuthSessionMapper authSessionMapper;

    private com.zhixing.TestAuth.SeededUser admin;

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        admin = com.zhixing.TestAuth.seed(authUserMapper, authSessionMapper, "notfound-admin-" + System.nanoTime(), "ADMIN");
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        com.zhixing.TestAuth.cleanup(authUserMapper, authSessionMapper, admin);
    }

    @Test
    void 未知路径返回A0004而非B0001() throws Exception {
        mockMvc.perform(get("/api/definitely-not-a-path")
                .header("Authorization", com.zhixing.TestAuth.bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0004"))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }
}
