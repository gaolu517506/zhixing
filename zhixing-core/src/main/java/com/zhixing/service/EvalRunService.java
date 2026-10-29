package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.config.EvalProperties;
import com.zhixing.core.eval.EvalSetSupport;
import com.zhixing.dao.entity.EvalRun;
import com.zhixing.dao.entity.KbChunk;
import com.zhixing.dao.entity.KbDocument;
import com.zhixing.dao.mapper.EvalRunMapper;
import com.zhixing.dao.mapper.KbChunkMapper;
import com.zhixing.dao.mapper.KbDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 验收向导编排（02-技术方案 §B6，F6，D20）：POST 同步段做权限判定、评估集解析（快失败）、
 * RUNNING 行落库与 EVAL_RUN 审计，随后交 eval-pool 异步执行——执行段在
 * {@link EvalRunExecutor}（独立 Bean：异步段写库与发起段隔离，结构对齐 QaService 的
 * 「入口同步 / 链路异步」拆分）。
 *
 * <p>EvalRunner/EvalMetrics 内核语义零改动：逐题执行序列、业务码=file_name 去扩展名、
 * 未命中记 -1 的容错语义由执行器与门控测试类 EvalRunner 逐行对齐；数学在
 * {@link EvalMetrics}、解析/分层在 {@link EvalSetSupport} 原样复用。
 */
@Slf4j
@Service
public class EvalRunService {

    private static final String STATUS_RUNNING = "RUNNING";

    private final EvalRunMapper runMapper;

    private final KbDocumentMapper docMapper;

    private final KbChunkMapper chunkMapper;

    private final EvalProperties evalProps;

    private final PermissionService permissionService;

    private final AuditRecorder audit;

    private final ObjectMapper objectMapper;

    private final EvalRunExecutor executor;

    private final Executor evalPool;

    public EvalRunService(EvalRunMapper runMapper, KbDocumentMapper docMapper, KbChunkMapper chunkMapper,
                          EvalProperties evalProps, PermissionService permissionService,
                          AuditRecorder audit, ObjectMapper objectMapper,
                          EvalRunExecutor executor,
                          @Qualifier("eval-pool") Executor evalPool) {
        this.runMapper = runMapper;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.evalProps = evalProps;
        this.permissionService = permissionService;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.evalPool = evalPool;
    }

    // ===== 请求/视图 =====

    public record StartRunRequest(Long kbId, String setPath, String setJson) {
    }

    /** GET 视图：config/report 为 jsonb 原样字符串，由前端解析。 */
    public record EvalRunView(Long id, Long kbId, Long createdBy, String status,
                              String config, String report, String errorMsg,
                              LocalDateTime createdAt, LocalDateTime finishedAt) {

        static EvalRunView of(EvalRun run) {
            return new EvalRunView(run.getId(), run.getKbId(), run.getCreatedBy(), run.getStatus(),
                    run.getConfig(), run.getReport(), run.getErrorMsg(),
                    run.getCreatedAt(), run.getFinishedAt());
        }
    }

    // ===== 发起（同步段） =====

    public EvalRunView start(StartRunRequest req) {
        permissionService.requireKbAdmin(req.kbId());
        boolean hasPath = req.setPath() != null && !req.setPath().isBlank();
        boolean hasJson = req.setJson() != null && !req.setJson().isBlank();
        if (hasPath == hasJson) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "setPath 与 setJson 必须二选一");
        }
        if (hasJson && req.setJson().length() > evalProps.getMaxInlineSetChars()) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR,
                    "内联评估集超过上限 " + evalProps.getMaxInlineSetChars() + " 字符，请改用 setPath");
        }

        List<EvalSetSupport.EvalCase> cases = hasPath
                ? EvalSetSupport.parse(resolveSetPath(req.setPath()))
                : parseInline(req.setJson());
        if (cases.isEmpty()) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "评估集为空（无有效用例行）");
        }

        CurrentUser actor = AuthContext.current();
        long runId = IdUtil.getSnowflakeNextId();
        EvalRun run = new EvalRun();
        run.setId(runId);
        run.setKbId(req.kbId());
        run.setCreatedBy(actor.id());
        run.setStatus(STATUS_RUNNING);
        String source = hasPath ? "path" : "inline";
        String config = configJson(req, hasPath, cases.size());
        run.setConfig(config);
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);

        String detailJson = startDetailJson(req.kbId(), source, cases.size());
        audit.record(actor.id(), actor.username(), AuditAction.EVAL_RUN.name(),
                "EVAL", String.valueOf(runId), AuditResult.OK, detailJson);
        List<EvalSetSupport.EvalCase> frozenCases = List.copyOf(cases);
        long frozenKbId = req.kbId();
        evalPool.execute(() -> executor.execute(runId, frozenKbId, frozenCases));
        return EvalRunView.of(run);
    }

    /**
     * setPath 收敛：仅接受仓库内相对 .jsonl 路径（拒绝绝对路径/.. 穿越/空字节），
     * 防止把任意服务器文件当评估集读。EvalSetSupport.resolve 仍保留仓库根向上锚定能力。
     */
    private Path resolveSetPath(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || !trimmed.endsWith(".jsonl") || trimmed.contains("..")
                || trimmed.contains("\0") || trimmed.startsWith("/")) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR,
                    "setPath 必须是仓库内相对的 .jsonl 路径（如 docs/scenario/eval-set.jsonl）");
        }
        return Path.of(trimmed);
    }

    // ===== 查询 =====

    public EvalRunView get(long runId) {
        EvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评估运行不存在：" + runId);
        }
        permissionService.requireAccess(run.getKbId());
        return EvalRunView.of(run);
    }

    public List<EvalRunView> list(long kbId) {
        permissionService.requireAccess(kbId);
        return runMapper.selectList(new LambdaQueryWrapper<EvalRun>()
                        .eq(EvalRun::getKbId, kbId)
                        .orderByDesc(EvalRun::getId))
                .stream().map(EvalRunView::of).toList();
    }

    // ===== 内部（同步段） =====

    /** 内联评估集解析：逐行 JSON（跳过空行与 # 注释），语义与 EvalSetSupport.parse(Path) 一致。 */
    private List<EvalSetSupport.EvalCase> parseInline(String setJson) {
        try {
            List<EvalSetSupport.EvalCase> cases = new ArrayList<>();
            for (String line : setJson.split("\n")) {
                if (line.isBlank() || line.strip().startsWith("#")) {
                    continue;
                }
                cases.add(objectMapper.readValue(line, EvalSetSupport.EvalCase.class));
            }
            return cases;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "内联评估集解析失败：" + e.getMessage());
        }
    }

    private String configJson(StartRunRequest req, boolean hasPath, int caseCount) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source", hasPath ? "path" : "inline");
        if (hasPath) {
            config.put("setPath", req.setPath());
        } else {
            config.put("setJsonChars", req.setJson().length());
            config.put("setJsonSha256", DigestUtil.sha256Hex(req.setJson()));
        }
        config.put("cases", caseCount);
        config.put("corpus", corpusOf(req.kbId()));
        return writeJson(config);
    }

    /** 语料规模快照（发起时点）：诚实标注回归结论的语料基数。 */
    private Map<String, Object> corpusOf(long kbId) {
        Map<String, Object> corpus = new LinkedHashMap<>();
        corpus.put("docs", docMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId)));
        corpus.put("chunks", chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getKbId, kbId)));
        return corpus;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("评估 config 序列化失败", e);
        }
    }

    private String startDetailJson(Long kbId, String source, int cases) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kbId", kbId);
            map.put("source", source);
            map.put("cases", cases);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }
}
