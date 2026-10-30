package com.zhixing.controller;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.TestAuth;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.AuditEventMapper;
import com.zhixing.dao.mapper.AuthSessionMapper;
import com.zhixing.dao.mapper.AuthUserMapper;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.OrgMemberMapper;
import com.zhixing.dao.entity.OrgMember;
import com.zhixing.service.OrgService;
import com.zhixing.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 线 F 真库集成（02 §B9 测试点，任务书 33-§1②/④）：跨组织隔离矩阵——组织 A 成员
 * 看不到组织 B 的库（判定链 / 列表收敛 / API 侧三层）；ORG_CHANGE 审计落库可查；
 * me.orgs additive；删除保护 API 侧。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrgIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthUserMapper authUserMapper;

    @Autowired
    private AuthSessionMapper authSessionMapper;

    @Autowired
    private KbKnowledgeBaseMapper kbMapper;

    @Autowired
    private AuditEventMapper auditMapper;

    @Autowired
    private OrgMemberMapper orgMemberMapper;

    @Autowired
    private com.zhixing.dao.mapper.OrganizationMapper orgMapper;

    @Autowired
    private OrgService orgService;

    @Autowired
    private PermissionService permissionService;

    private final List<TestAuth.SeededUser> seeded = new ArrayList<>();

    private final List<Long> kbIds = new ArrayList<>();

    private final List<Long> orgIds = new ArrayList<>();

    private TestAuth.SeededUser seedUser(String name, String systemRole) {
        TestAuth.SeededUser u = TestAuth.seed(authUserMapper, authSessionMapper, name, systemRole);
        seeded.add(u);
        return u;
    }

    private long seedKb(String name, String visibility, Long orgId) {
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(IdUtil.getSnowflakeNextId());
        kb.setName(name);
        kb.setVisibility(visibility);
        kb.setOrgId(orgId);
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        kbIds.add(kb.getId());
        return kb.getId();
    }

    private long seedOrg(String name) {
        CurrentUser root = new CurrentUser(0L, "it-admin", "ADMIN", AuthChannel.WEB);
        AuthContext.set(root);
        OrgService.OrgView view = orgService.create(new OrgService.CreateOrgRequest(name));
        AuthContext.clear();
        orgIds.add(view.id());
        return view.id();
    }

    @AfterEach
    void cleanup() {
        for (TestAuth.SeededUser u : seeded) {
            TestAuth.cleanup(authUserMapper, authSessionMapper, u);
        }
        seeded.clear();
        // 删库行即解除归属；成员行与组织行随后清（删除保护测试留下的非空组织也能清干净）
        for (Long kbId : kbIds) {
            kbMapper.deleteById(kbId);
        }
        kbIds.clear();
        for (Long orgId : orgIds) {
            orgMemberMapper.delete(new LambdaQueryWrapper<OrgMember>().eq(OrgMember::getOrgId, orgId));
            orgMapper.deleteById(orgId);
        }
        orgIds.clear();
    }

    @Test
    void 隔离矩阵_组织A成员看不到组织B的库_三层断言() {
        long orgA = seedOrg("隔离矩阵-售后部-" + System.nanoTime());
        long orgB = seedOrg("隔离矩阵-研发部-" + System.nanoTime());
        long kbA = seedKb("售后内部库", "PRIVATE", orgA);
        long kbB = seedKb("研发内部库", "PRIVATE", orgB);

        TestAuth.SeededUser userA = seedUser("org-a-user-" + System.nanoTime(), "USER");
        // A 入组织 A（MEMBER）
        AuthContext.set(new CurrentUser(0L, "it-admin", "ADMIN", AuthChannel.WEB));
        orgService.putMembers(orgA, new OrgService.PutMembersRequest(List.of(
                new OrgService.MemberUpsert(userA.id(), "MEMBER"))));
        AuthContext.clear();

        // 登录为 A 成员
        AuthContext.set(new CurrentUser(userA.id(), userA.username(), "USER", AuthChannel.WEB));

        // ① 判定链：可见 A 库、不可见 B 库
        assertThat(permissionService.accessibleKbIds()).contains(kbA).doesNotContain(kbB);
        assertThatThrownBy(() -> permissionService.requireAccess(kbB)).isInstanceOf(Exception.class);

        // ② 列表收敛（/api/kb）：不含 B 库
        String bearer = TestAuth.bearer(userA);
        try {
            mockMvc.perform(get("/api/kb").header("Authorization", bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == '" + kbB + "')]").isEmpty());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // ③ API 侧（requireAccess 同权）：A 成员访问 B 库文档列表 → 403/A0006
        try {
            mockMvc.perform(get("/api/kb/" + kbB + "/documents").header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("A0006"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        AuthContext.clear();
    }

    @Test
    void ORG_CHANGE审计落库可查_me扩展orgs_删除保护() throws Exception {
        TestAuth.SeededUser admin = seedUser("org-it-admin-" + System.nanoTime(), "ADMIN");
        TestAuth.SeededUser plain = seedUser("org-plain-" + System.nanoTime(), "USER");
        long org = seedOrg("审计组织-" + System.nanoTime());
        long kb = seedKb("审计归属库", "PRIVATE", null);

        // 系统 ADMIN 经 API 归属成员 + 库（差量各一条 ORG_CHANGE）
        String memberBody = "[{\"userId\":" + plain.id() + ",\"role\":\"ADMIN\"}]";
        mockMvc.perform(put("/api/admin/orgs/" + org + "/members")
                        .header("Authorization", TestAuth.bearer(admin))
                        .contentType("application/json")
                        .content("{\"members\":" + memberBody + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"));
        mockMvc.perform(put("/api/admin/orgs/" + org + "/kbs")
                        .header("Authorization", TestAuth.bearer(admin))
                        .contentType("application/json")
                        .content("{\"kbIds\": [" + kb + "]}"))
                .andExpect(status().isOk());

        // 审计落库可查（两条 ORG_CHANGE：members + kbAssignments）
        List<AuditEvent> events = auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .eq(AuditEvent::getAction, "ORG_CHANGE")
                .eq(AuditEvent::getTargetId, String.valueOf(org)));
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getDetail()).contains("scope");

        // me.orgs additive：plain 已是组织 ADMIN，me 带组织
        mockMvc.perform(get("/api/auth/me").header("Authorization", TestAuth.bearer(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgs[0].id").value(org))
                .andExpect(jsonPath("$.data.orgs[0].role").value("ADMIN"));

        // 组织管理员（plain=ADMIN）可管理本组织；删除组织被保护（有成员）
        mockMvc.perform(put("/api/admin/orgs/" + org + "/members")
                        .header("Authorization", TestAuth.bearer(plain))
                        .contentType("application/json")
                        .content("[{\"userId\":" + plain.id() + ",\"role\":\"ADMIN\"}]"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/orgs")
                        .header("Authorization", TestAuth.bearer(plain)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
        // 删除保护：仍有成员 + 归属库 → orgService.delete 拒绝（org 行由 cleanup 清理）
        assertThatThrownBy(() -> {
            AuthContext.set(new CurrentUser(admin.id(), admin.username(), "ADMIN", AuthChannel.WEB));
            try {
                orgService.delete(org);
            } finally {
                AuthContext.clear();
            }
        }).isInstanceOf(Exception.class);
    }
}
