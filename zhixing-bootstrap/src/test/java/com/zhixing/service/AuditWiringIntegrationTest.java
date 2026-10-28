package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AgentRun;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.KbMember;
import com.zhixing.dao.mapper.AgentRunMapper;
import com.zhixing.dao.mapper.AuditEventMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.dao.mapper.KbMemberMapper;
import com.zhixing.infra.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计全链路（02 §B3 测试点，任务书验收③）：上传→删除→确认各一条；动作真实落库后
 * audit_event 有对应事件（actor 为当前身份）；审计失败不阻塞主链路的实现级断言在
 * AuditRecorderTest（mock 落库异常）。confirm 走真 ConfirmManager/CAS/toolRegistry。
 * trace 关（免孤儿节点）、LLM 指向不可路由端口（续跑段快速终态，不外呼真实供应商）。
 */
@SpringBootTest(properties = {
        "zhixing.trace.enabled=false",
        "zhixing.llm.providers.deepseek.base-url=http://127.0.0.1:1",
        "zhixing.llm.providers.zhipu.base-url=http://127.0.0.1:1",
        "zhixing.llm.chat.timeout-ms=2000",
        "zhixing.llm.chat.first-token-timeout-ms=1000"
})
class AuditWiringIntegrationTest {

    @Autowired
    private KbService kbService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private KbKnowledgeBaseMapper kbMapper;

    @Autowired
    private KbMemberMapper memberMapper;

    @Autowired
    private KbDocumentMapper docMapper;

    @Autowired
    private AgentRunMapper runMapper;

    @Autowired
    private AuditEventMapper auditMapper;

    @Autowired
    private FileStorage fileStorage;

    private static final CurrentUser USER = new CurrentUser(990001L, "audit-user", "USER", AuthChannel.WEB);

    private final List<Long> kbIds = new ArrayList<>();

    private final List<Long> runIds = new ArrayList<>();

    private final List<Long> docIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AuthContext.set(USER);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        for (Long docId : docIds) {
            docMapper.deleteById(docId);
            try {
                fileStorage.deleteDocDir(docId);
            } catch (Exception ignored) {
                // 磁盘清理失败不影响 DB 收尾
            }
        }
        docIds.clear();
        for (Long runId : runIds) {
            runMapper.deleteById(runId);
        }
        runIds.clear();
        for (Long kbId : kbIds) {
            memberMapper.delete(new LambdaQueryWrapper<KbMember>().eq(KbMember::getKbId, kbId));
            kbMapper.deleteById(kbId);
        }
        kbIds.clear();
        // 本测试产生的审计行按 actor 精确回收（仅追加表，测试也只删自己播的）
        auditMapper.delete(new LambdaQueryWrapper<AuditEvent>()
                .eq(AuditEvent::getActorId, USER.id())
                .in(AuditEvent::getAction, List.of("KB_CREATE", "DOC_UPLOAD", "DOC_DELETE",
                        "AGENT_CONFIRM_APPROVE", "AGENT_CONFIRM_REJECT"))
                .ge(AuditEvent::getCreatedAt, LocalDateTime.now().minusMinutes(10)));
    }

    private long seedKb(String name) {
        KbKnowledgeBase kb = kbService.createKb(name, null);
        kbIds.add(kb.getId());
        return kb.getId();
    }

    private long seedWaitingRun(long kbId, String pendingTool) {
        AgentRun run = new AgentRun();
        run.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
        run.setSessionId(1L);
        run.setKbId(kbId);
        run.setUserId(USER.id());
        run.setInput("审计确认链路");
        run.setStatus("WAITING_CONFIRM");
        run.setIterations(1);
        run.setMessagesSnapshot("[{\"role\":\"system\",\"content\":\"test\"}]");
        run.setPendingTool("{\"toolCallId\":\"call-1\",\"name\":\"" + pendingTool + "\",\"arguments\":\"{}\"}");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(run);
        runIds.add(run.getId());
        return run.getId();
    }

    private List<AuditEvent> auditEvents(String action, String targetId) {
        return auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .eq(AuditEvent::getActorId, USER.id())
                .eq(AuditEvent::getAction, action)
                .eq(targetId != null, AuditEvent::getTargetId, targetId)
                .orderByDesc(AuditEvent::getCreatedAt));
    }

    @Test
    void 上传与删除各落一条DOC事件_确认批准落APPROVE事件() throws Exception {
        long kbId = seedKb("审计链路-" + System.nanoTime());

        // ① 上传 → DOC_UPLOAD
        KbService.UploadOutcome upload = kbService.upload(kbId, new MockMultipartFile(
                "file", "audit.txt", "text/plain", "审计链路内容".getBytes()));
        assertThat(upload.idempotent()).isFalse();
        docIds.add(upload.document().getId());
        List<AuditEvent> uploads = auditEvents("DOC_UPLOAD", String.valueOf(upload.document().getId()));
        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0).getResult()).isEqualTo("OK");
        assertThat(uploads.get(0).getDetail()).contains("audit.txt");

        // ② 删除 → DOC_DELETE
        kbService.deleteDocument(upload.document().getId());
        List<AuditEvent> deletes = auditEvents("DOC_DELETE", String.valueOf(upload.document().getId()));
        assertThat(deletes).hasSize(1);

        // ③ 确认批准（真 CAS + 真 get_current_time 工具）→ AGENT_CONFIRM_APPROVE
        long runId = seedWaitingRun(kbId, "get_current_time");
        SseEmitter emitter = agentService.confirm(runId, true);
        assertThat(emitter).isNotNull();
        List<AuditEvent> approves = auditEvents("AGENT_CONFIRM_APPROVE", String.valueOf(runId));
        assertThat(approves).hasSize(1);
        assertThat(approves.get(0).getDetail()).contains("get_current_time");

        // 续跑异步段：等链路自然终态，避免后台写与清理竞争
        Thread.sleep(1500);
    }

    @Test
    void 确认拒绝落REJECT事件_且不执行工具路径() throws Exception {
        long kbId = seedKb("审计拒绝-" + System.nanoTime());
        long runId = seedWaitingRun(kbId, "delete_document");
        SseEmitter emitter = agentService.confirm(runId, false);
        assertThat(emitter).isNotNull();
        List<AuditEvent> rejects = auditEvents("AGENT_CONFIRM_REJECT", String.valueOf(runId));
        assertThat(rejects).hasSize(1);
        assertThat(auditEvents("AGENT_CONFIRM_APPROVE", String.valueOf(runId))).isEmpty();
        Thread.sleep(1500);
    }

    @Test
    void 建库落KB_CREATE事件_创建人自动成为库管理员() {
        long kbId = seedKb("审计建库-" + System.nanoTime());
        List<AuditEvent> creates = auditEvents("KB_CREATE", String.valueOf(kbId));
        assertThat(creates).hasSize(1);
        KbMember owner = memberMapper.selectOne(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, USER.id()));
        assertThat(owner).isNotNull();
        assertThat(owner.getRole()).isEqualTo("ADMIN");
    }
}
