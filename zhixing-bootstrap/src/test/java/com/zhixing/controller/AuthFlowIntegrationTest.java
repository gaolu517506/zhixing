package com.zhixing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.TestAuth;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.entity.AuthUser;
import com.zhixing.dao.mapper.AuditEventMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证流程集成（02 §B1 测试点）：登录成功/失败、防枚举统一文案、连续失败锁定、
 * 登出即吊销、禁用即失效、setup 空表语义（真库非空 → 拒绝；空表分支在 AuthServiceTest 单测）、
 * 审计 LOGIN OK/FAIL 事件。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthUserMapper userMapper;

    @Autowired
    private AuditEventMapper auditMapper;

    private final List<TestAuth.SeededUser> seeded = new ArrayList<>();

    private final List<Long> seededAuditIds = new ArrayList<>();

    private TestAuth.SeededUser user;

    @BeforeEach
    void seed() {
        user = TestAuth.seed(userMapper, null, "auth-flow-" + System.nanoTime(), "USER");
        seeded.add(user);
    }

    @AfterEach
    void cleanup() {
        for (TestAuth.SeededUser u : seeded) {
            TestAuth.cleanup(userMapper, null, u);
        }
        seeded.clear();
        // 测试只清理自己产生的审计行（播种 id），不触碰他人数据
        for (Long id : seededAuditIds) {
            auditMapper.deleteById(id);
        }
        seededAuditIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode loginNode(String username, String password) throws Exception {
        return objectMapper.readTree(login(username, password));
    }

    private void collectAudit(Long actorId) {
        auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>().eq(AuditEvent::getActorId, actorId))
                .forEach(e -> seededAuditIds.add(e.getId()));
        auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>().eq(AuditEvent::getActorName, user.username()))
                .forEach(e -> seededAuditIds.add(e.getId()));
    }

    @Test
    void 登录成功_带token访问me_登出后失效() throws Exception {
        JsonNode ok = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + user.username() + "\",\"password\":\""
                                + TestAuth.plaintextPassword() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn().getResponse().getContentAsString());
        String token = ok.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(ok.path("data").path("user").path("username").asText()).isEqualTo(user.username());
        // Long→String 全局序列化
        assertThat(ok.path("data").path("user").path("id").isTextual()).isTrue();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(user.username()));

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        collectAudit(user.id());
    }

    @Test
    void 防枚举_用户不存在与密码错误同一文案() throws Exception {
        String unknown = login("no-such-user-" + System.nanoTime(), "whatever");
        String wrongPassword = login(user.username(), "wrong-password");
        assertThat(objectMapper.readTree(unknown).path("message").asText())
                .isEqualTo(objectMapper.readTree(wrongPassword).path("message").asText())
                .isEqualTo("账号或密码错误");
        collectAudit(user.id());
    }

    @Test
    void 连续失败5次锁定_正确密码也被拒() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").contentType("application/json")
                            .content("{\"username\":\"" + user.username() + "\",\"password\":\"bad-" + i + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        String locked = login(user.username(), TestAuth.plaintextPassword());
        JsonNode node = objectMapper.readTree(locked);
        assertThat(node.path("code").asText()).isEqualTo("A0005");
        assertThat(node.path("message").asText()).contains("锁定");
        collectAudit(user.id());
    }

    @Test
    void 禁用即失效_登录也拒绝() throws Exception {
        // 先登录拿 token
        JsonNode ok = loginNode(user.username(), TestAuth.plaintextPassword());
        String token = ok.path("data").path("token").asText();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 禁用后：存量 token 立即失效（校验链查 status）
        AuthUser patch = new AuthUser();
        patch.setId(user.id());
        patch.setStatus("DISABLED");
        userMapper.updateById(patch);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // 禁用账号即使密码正确也拒绝，且不泄露状态（防枚举同一文案）
        String login = login(user.username(), TestAuth.plaintextPassword());
        assertThat(objectMapper.readTree(login).path("message").asText()).isEqualTo("账号或密码错误");
        collectAudit(user.id());
    }

    @Test
    void setup_真库已初始化_状态false且重复初始化被拒() throws Exception {
        mockMvc.perform(get("/api/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needSetup").value(false));
        mockMvc.perform(post("/api/auth/setup").contentType("application/json")
                        .content("{\"username\":\"hacker-" + System.nanoTime() + "\",\"password\":\"pass-123456\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }

    @Test
    void 登录成功与失败均落审计LOGIN事件() throws Exception {
        login(user.username(), "wrong-password");
        login(user.username(), TestAuth.plaintextPassword());
        List<AuditEvent> events = auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .eq(AuditEvent::getActorName, user.username())
                .eq(AuditEvent::getAction, "LOGIN")
                .orderByDesc(AuditEvent::getCreatedAt));
        assertThat(events).hasSizeGreaterThanOrEqualTo(2);
        assertThat(events).extracting(AuditEvent::getResult)
                .contains("OK", "FAIL");
        collectAudit(user.id());
    }
}
