package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.auth.AuthChannel;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.entity.EvalRun;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.entity.KbMember;
import com.zhixing.dao.mapper.AuditEventMapper;
import com.zhixing.dao.mapper.EvalRunMapper;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验收向导真库集成（02 §B6，任务书验收①）：eval_run 生命周期 RUNNING → COMPLETED
 * （检索引擎对空库返回空 fused → 指标全 0 是合法完成）+ EVAL_RUN 发起审计 +
 * 语料规模快照。检索走真引擎（库内无文档 → 双通道空结果，零 LLM/embedding 消耗）。
 */
@SpringBootTest(properties = "zhixing.trace.enabled=false")
class EvalRunLifecycleIntegrationTest {

    @Autowired
    private EvalRunService evalRunService;

    @Autowired
    private EvalRunMapper runMapper;

    @Autowired
    private KbService kbService;

    @Autowired
    private KbKnowledgeBaseMapper kbMapper;

    @Autowired
    private KbMemberMapper memberMapper;

    @Autowired
    private KbDocumentMapper docMapper;

    @Autowired
    private AuditEventMapper auditMapper;

    @Autowired
    private FileStorage fileStorage;

    private static final CurrentUser OWNER =
            new CurrentUser(7770001L, "eval-owner", "USER", AuthChannel.WEB);

    private long kbId;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        AuthContext.set(OWNER);
        KbKnowledgeBase kb = kbService.createKb("评测向导-" + System.nanoTime(), null);
        kbId = kb.getId();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        try {
            for (KbDocument doc : docMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                    .eq(KbDocument::getKbId, kbId))) {
                docMapper.deleteById(doc.getId());
                try {
                    fileStorage.deleteDocDir(doc.getId());
                } catch (Exception ignored) {
                    // 磁盘清理失败不影响 DB 收尾
                }
            }
            memberMapper.delete(new LambdaQueryWrapper<KbMember>().eq(KbMember::getKbId, kbId));
            kbMapper.deleteById(kbId);
            runMapper.delete(new LambdaQueryWrapper<EvalRun>().eq(EvalRun::getKbId, kbId));
            auditMapper.delete(new LambdaQueryWrapper<AuditEvent>()
                    .eq(AuditEvent::getActorId, OWNER.id())
                    .eq(AuditEvent::getAction, "EVAL_RUN"));
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void 生命周期_空库评测快速COMPLETED_指标全零_含规模快照与EVAL_RUN审计() throws Exception {
        String setJson = """
                {"id":"c1","query":"测试问题一","expected_doc_ids":["01-参数"],"difficulty":"easy"}
                {"id":"c2","query":"测试问题二","expected_doc_ids":["02-保修"],"difficulty":"medium"}
                """;

        EvalRunService.EvalRunView started =
                evalRunService.start(new EvalRunService.StartRunRequest(kbId, null, setJson));

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.createdBy()).isEqualTo(OWNER.id());

        EvalRun run = awaitTerminal(started.id());
        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        assertThat(run.getFinishedAt()).isNotNull();
        // jsonb 走全局 ObjectMapper（带空格分隔、Long→String），断言用语义解析而非子串
        com.fasterxml.jackson.databind.JsonNode report = MAPPER.readTree(run.getReport());
        assertThat(report.path("hitAt5").asDouble()).isZero();
        assertThat(report.path("cases").asInt()).isEqualTo(2);
        assertThat(report.path("delta").isNull()).isTrue();
        assertThat(report.path("corpus").path("docs").asLong()).isZero();
        assertThat(report.path("corpus").path("chunks").asLong()).isZero();
        assertThat(MAPPER.readTree(run.getConfig()).path("source").asText()).isEqualTo("inline");

        // EVAL_RUN 发起审计（actor=库管理员）
        List<AuditEvent> events = auditMapper.selectList(new LambdaQueryWrapper<AuditEvent>()
                .eq(AuditEvent::getAction, "EVAL_RUN")
                .eq(AuditEvent::getTargetId, String.valueOf(started.id())));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActorId()).isEqualTo(OWNER.id());
        assertThat(events.get(0).getResult()).isEqualTo("OK");
    }

    @Test
    void 第二次运行_delta指向第一次COMPLETED运行() throws Exception {
        String setJson = """
                {"id":"c1","query":"delta 测试","expected_doc_ids":["01-参数"],"difficulty":"easy"}
                """;

        EvalRunService.EvalRunView first =
                evalRunService.start(new EvalRunService.StartRunRequest(kbId, null, setJson));
        awaitTerminal(first.id());

        EvalRunService.EvalRunView second =
                evalRunService.start(new EvalRunService.StartRunRequest(kbId, null, setJson));
        awaitTerminal(second.id());

        EvalRun secondRun = runMapper.selectById(second.id());
        com.fasterxml.jackson.databind.JsonNode delta = MAPPER.readTree(secondRun.getReport()).path("delta");
        assertThat(delta.path("vsLastRunId").asLong()).isEqualTo(first.id());
        // 两次都是空库全零：delta = 0.0
        assertThat(delta.path("hitAt5").asDouble()).isZero();
    }

    /** 轮询至终态（空库检索毫秒级；10s 上限兜底线程池调度抖动）。 */
    private EvalRun awaitTerminal(long runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        EvalRun run = runMapper.selectById(runId);
        while (run != null && "RUNNING".equals(run.getStatus()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            run = runMapper.selectById(runId);
        }
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo("COMPLETED");
        return run;
    }

    @Test
    void get_不存在运行返回NOT_FOUND() {
        com.zhixing.common.auth.AuthContext.set(OWNER);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evalRunService.get(1L))
                .isInstanceOf(com.zhixing.common.BusinessException.class)
                .hasMessageContaining("评估运行不存在");
    }
}
