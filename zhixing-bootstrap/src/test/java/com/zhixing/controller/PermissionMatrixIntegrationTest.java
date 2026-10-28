package com.zhixing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.TestAuth;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.KbMember;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.entity.QaSession;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.KbMemberMapper;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import com.zhixing.infra.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限矩阵 16 组（PRD 最小新增集，02 §B2 测试点）：4 主体（未认证/成员/库管理员/系统管理员）
 * × 4 动作（读列表/提问 SSE/上传/删库）。库 PRIVATE，库管理员=创建人（member ADMIN）。
 * LLM/embedding 指向不可路由端口 + 短超时：授权路径的 SSE 快速走 error+done 终态
 * （断言 asyncStarted 即权限放行），未授权路径在 service 入口即 403/A0006。
 */
@SpringBootTest(properties = {
        "zhixing.llm.providers.deepseek.base-url=http://127.0.0.1:1",
        "zhixing.llm.providers.zhipu.base-url=http://127.0.0.1:1",
        "zhixing.llm.providers.siliconflow.base-url=http://127.0.0.1:1",
        "zhixing.llm.chat.timeout-ms=2000",
        "zhixing.llm.chat.first-token-timeout-ms=1000",
        "zhixing.trace.enabled=false"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PermissionMatrixIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KbKnowledgeBaseMapper kbMapper;

    @Autowired
    private KbMemberMapper memberMapper;

    @Autowired
    private KbDocumentMapper docMapper;

    @Autowired
    private QaSessionMapper sessionMapper;

    @Autowired
    private QaMessageMapper messageMapper;

    @Autowired
    private FileStorage fileStorage;

    private TestAuth.SeededUser sysadmin;
    private TestAuth.SeededUser owner;
    private TestAuth.SeededUser member;
    private TestAuth.SeededUser outsider;

    private long kbId;
    private long kbIdForAdminDelete;

    private final List<TestAuth.SeededUser> seededUsers = new ArrayList<>();

    @BeforeEach
    void seed() {
        String tag = String.valueOf(System.nanoTime());
        sysadmin = TestAuth.seed(authUserMapper, authSessionMapper, "pm-admin-" + tag, "ADMIN");
        owner = TestAuth.seed(authUserMapper, authSessionMapper, "pm-owner-" + tag, "USER");
        member = TestAuth.seed(authUserMapper, authSessionMapper, "pm-member-" + tag, "USER");
        outsider = TestAuth.seed(authUserMapper, authSessionMapper, "pm-outsider-" + tag, "USER");
        seededUsers.add(sysadmin);
        seededUsers.add(owner);
        seededUsers.add(member);
        seededUsers.add(outsider);

        kbId = seedKb("pm-kb-主库-" + tag, owner.id(), member.id());
        kbIdForAdminDelete = seedKb("pm-kb-管理员删-" + tag, owner.id(), null);
    }

    @Autowired
    private com.zhixing.dao.mapper.AuthUserMapper authUserMapper;

    @Autowired
    private com.zhixing.dao.mapper.AuthSessionMapper authSessionMapper;

    private long seedKb(String name, Long ownerUserId, Long memberUserId) {
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
        kb.setName(name);
        kb.setCreatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        if (ownerUserId != null) {
            insertMember(kb.getId(), ownerUserId, "ADMIN");
        }
        if (memberUserId != null) {
            insertMember(kb.getId(), memberUserId, "MEMBER");
        }
        return kb.getId();
    }

    private void insertMember(long kb, long userId, String role) {
        KbMember m = new KbMember();
        m.setKbId(kb);
        m.setUserId(userId);
        m.setRole(role);
        m.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(m);
    }

    @AfterEach
    void cleanup() {
        for (long kb : new long[]{kbId, kbIdForAdminDelete}) {
            List<KbDocument> docs = docMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                    .eq(KbDocument::getKbId, kb));
            for (KbDocument doc : docs) {
                docMapper.deleteById(doc.getId());
                try {
                    fileStorage.deleteDocDir(doc.getId());
                } catch (Exception ignored) {
                    // 磁盘清理失败不影响 DB 收尾
                }
            }
            memberMapper.delete(new LambdaQueryWrapper<KbMember>().eq(KbMember::getKbId, kb));
            kbMapper.deleteById(kb);
        }
        // 提问单元格产生的会话（标题前缀唯一）：先消息后会话
        List<QaSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<QaSession>()
                .likeRight(QaSession::getTitle, "matrix-qa-"));
        for (QaSession s : sessions) {
            messageMapper.delete(new LambdaQueryWrapper<QaMessage>().eq(QaMessage::getSessionId, s.getId()));
            sessionMapper.deleteById(s.getId());
        }
        for (TestAuth.SeededUser u : seededUsers) {
            TestAuth.cleanup(authUserMapper, authSessionMapper, u);
        }
        seededUsers.clear();
    }

    // ===== 动作实现 =====

    private String readList(String bearer) throws Exception {
        return mockMvc.perform(get("/api/kb").header("Authorization", bearer))
                .andReturn().getResponse().getContentAsString();
    }

    private MvcResult askSse(String bearer, long kb) throws Exception {
        return mockMvc.perform(post("/api/qa/stream")
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"kbId\":" + kb + ",\"question\":\"matrix-qa-" + System.nanoTime()
                                + " 演示库介绍\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions upload(String bearer, long kb) throws Exception {
        // .file 必须在 .header 之前链（multipart builder 调父类方法后退化为普通 builder）
        return mockMvc.perform(multipart("/api/kb/" + kb + "/documents")
                .file(new MockMultipartFile("file", "matrix.txt", "text/plain",
                        "matrix content".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", bearer));
    }

    private org.springframework.test.web.servlet.ResultActions deleteKb(String bearer, long kb) throws Exception {
        return mockMvc.perform(delete("/api/kb/" + kb).header("Authorization", bearer));
    }

    private static final String BEARER_OF = "Bearer ";

    // ===== ① 读列表 =====

    @Test
    @Order(1)
    void 矩阵_读列表() throws Exception {
        // 未认证
        mockMvc.perform(get("/api/kb")).andExpect(status().isUnauthorized());
        // 成员：可见所在库
        String memberView = readList(BEARER_OF + member.token());
        assertThat(memberView).contains(String.valueOf(kbId));
        // 库管理员：可见所在库
        String ownerView = readList(BEARER_OF + owner.token());
        assertThat(ownerView).contains(String.valueOf(kbId));
        // 系统管理员：字面口径 ALL ∪ 直属成员——本库 PRIVATE 且其非成员，列表不出现但状态 200
        String adminView = readList(BEARER_OF + sysadmin.token());
        assertThat(adminView).doesNotContain(String.valueOf(kbId));
        // 局外人：不可见
        String outsiderView = readList(BEARER_OF + outsider.token());
        assertThat(outsiderView).doesNotContain(String.valueOf(kbId));
    }

    // ===== ② 提问 SSE（授权 = SSE 建立并进入链路；未授权 = 入口 403） =====

    @Test
    @Order(2)
    void 矩阵_提问SSE() throws Exception {
        mockMvc.perform(post("/api/qa/stream").contentType("application/json")
                        .content("{\"kbId\":" + kbId + ",\"question\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        // 成员 / 库管理员 / 系统管理员：SSE 建立（asyncStarted），链路在无 LLM 下快速终态
        MvcResult memberResult = askSse(BEARER_OF + member.token(), kbId);
        memberResult.getAsyncResult(15000);
        mockMvc.perform(asyncDispatch(memberResult)).andExpect(status().isOk());

        MvcResult ownerResult = askSse(BEARER_OF + owner.token(), kbId);
        ownerResult.getAsyncResult(15000);
        mockMvc.perform(asyncDispatch(ownerResult)).andExpect(status().isOk());

        MvcResult adminResult = askSse(BEARER_OF + sysadmin.token(), kbId);
        adminResult.getAsyncResult(15000);
        mockMvc.perform(asyncDispatch(adminResult)).andExpect(status().isOk());

        // 局外人：service 入口 403 + A0006（无 SSE）
        mockMvc.perform(post("/api/qa/stream")
                        .header("Authorization", BEARER_OF + outsider.token())
                        .contentType("application/json")
                        .content("{\"kbId\":" + kbId + ",\"question\":\"matrix-qa-拒绝\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }

    // ===== ③ 上传 =====

    @Test
    @Order(3)
    void 矩阵_上传() throws Exception {
        mockMvc.perform(multipart("/api/kb/" + kbId + "/documents"))
                .andExpect(status().isUnauthorized());

        upload(BEARER_OF + member.token(), kbId).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        upload(BEARER_OF + owner.token(), kbId).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        upload(BEARER_OF + sysadmin.token(), kbId).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        upload(BEARER_OF + outsider.token(), kbId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }

    // ===== ④ 删库（最后执行：成功单元格会真删） =====

    @Test
    @Order(4)
    void 矩阵_删库() throws Exception {
        mockMvc.perform(delete("/api/kb/" + kbId)).andExpect(status().isUnauthorized());

        // 成员可写不可删：403
        deleteKb(BEARER_OF + member.token(), kbId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
        // 局外人：403
        deleteKb(BEARER_OF + outsider.token(), kbId).andExpect(status().isForbidden());
        // 系统管理员：200（删独立库 kbIdForAdminDelete，保住主库给库管理员单元格）
        deleteKb(BEARER_OF + sysadmin.token(), kbIdForAdminDelete).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        // 库管理员：200
        deleteKb(BEARER_OF + owner.token(), kbId).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
    }
}
