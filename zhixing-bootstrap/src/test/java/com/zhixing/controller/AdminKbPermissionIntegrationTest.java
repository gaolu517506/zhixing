package com.zhixing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.TestAuth;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.dao.mapper.AuditEventMapper;
import com.zhixing.dao.mapper.AuthSessionMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.KbMember;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.KbMemberMapper;
import com.zhixing.service.AdminKbAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 真库集成：PERMISSION_CHANGE 审计全链路（visibility 变更 → 审计事件落库可查）、
 * setup-status 契约（seedAvailable=true）、非空表 setup 拒绝（向导幂等语义）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminKbPermissionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthUserMapper authUserMapper;

    @Autowired
    private AuthSessionMapper authSessionMapper;

    @Autowired
    private KbKnowledgeBaseMapper kbMapper;

    @Autowired
    private KbMemberMapper memberMapper;

    @Autowired
    private AuditEventMapper auditMapper;

    @Autowired
    private AdminKbAdminService kbAdminService;

    private TestAuth.SeededUser admin;

    private long kbId;

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        admin = TestAuth.seed(authUserMapper, authSessionMapper, "m2-kb-admin-" + System.nanoTime(), "ADMIN");
        kbId = 990000L + (System.nanoTime() % 100000);
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(kbId);
        kb.setName("m2-权限集成-" + System.nanoTime());
        kb.setCreatedAt(java.time.LocalDateTime.now());
        kbMapper.insert(kb);
    }

    @AfterEach
    void cleanup() {
        TestAuth.cleanup(authUserMapper, authSessionMapper, admin);
        memberMapper.delete(new LambdaQueryWrapper<KbMember>().eq(KbMember::getKbId, kbId));
        kbMapper.deleteById(kbId);
        auditMapper.delete(new LambdaQueryWrapper<AuditEvent>()
                .eq(AuditEvent::getAction, "PERMISSION_CHANGE")
                .eq(AuditEvent::getTargetId, String.valueOf(kbId)));
    }

    @Test
    void visibility变更全链路_改库_落审计_审计页可查() throws Exception {
        String bearer = TestAuth.bearer(admin);

        mockMvc.perform(patch("/api/admin/kbs/" + kbId)
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"visibility\":\"ALL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("ALL"));

        // 库数据真实变更
        assertThat(kbMapper.selectById(kbId).getVisibility()).isEqualTo("ALL");
        // 审计事件真实落库（from→to detail）
        List<AuditEvent> events = auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .eq(AuditEvent::getAction, "PERMISSION_CHANGE")
                .eq(AuditEvent::getTargetId, String.valueOf(kbId)));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getDetail()).contains("\"from\"").contains("PRIVATE").contains("ALL");

        // 审计页可查（线 A 端点 + 本线事件接线打通）
        mockMvc.perform(get("/api/admin/audit").header("Authorization", bearer)
                        .param("action", "PERMISSION_CHANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void setupStatus契约_非空表needSetup为false且seedAvailable为true() throws Exception {
        TestAuth.SeededUser anyone = TestAuth.seed(authUserMapper, authSessionMapper,
                "m2-status-" + System.nanoTime(), "USER");
        try {
            mockMvc.perform(get("/api/auth/setup-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.needSetup").value(false))
                    .andExpect(jsonPath("$.data.seedAvailable").value(true));
        } finally {
            TestAuth.cleanup(authUserMapper, authSessionMapper, anyone);
        }
    }

    @Test
    void setup_非空表拒绝向导() throws Exception {
        mockMvc.perform(post("/api/auth/setup")
                        .contentType("application/json")
                        .content("{\"username\":\"m2-hacker-" + System.nanoTime()
                                + "\",\"password\":\"pass-123456\",\"seed\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }
}
