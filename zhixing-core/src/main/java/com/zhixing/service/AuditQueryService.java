package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.mapper.AuditEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计查询与导出（02-技术方案 §B3）：仅系统 ADMIN（门禁在 service 入口，controller 零判断）。
 * 查询条件 from/to/actorId/action + 分页；导出走 keyset（id > lastId）分批拉取流式写 CSV，
 * 不整表进内存。只读——与 AuditRecorder 的仅追加共同构成「审计不可改写」闭环。
 */
@Slf4j
@Service
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    /** 单批导出行数（keyset 游标步长）。 */
    private static final int EXPORT_BATCH = 1000;

    /** 导出行数上限：审计表按索引滚动增长，给导出一个明确的护栏而非无界拉取（超限截断并留日志）。 */
    private static final long EXPORT_MAX_ROWS = 100_000;

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AuditEventMapper auditMapper;

    public AuditQueryService(AuditEventMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    // ===== 查询条件与视图 =====

    public record AuditQuery(LocalDateTime from, LocalDateTime to, Long actorId, String action) {

        private LambdaQueryWrapper<AuditEvent> toWrapper() {
            return new LambdaQueryWrapper<AuditEvent>()
                    .ge(from != null, AuditEvent::getCreatedAt, from)
                    .le(to != null, AuditEvent::getCreatedAt, to)
                    .eq(actorId != null, AuditEvent::getActorId, actorId)
                    .eq(action != null && !action.isBlank(), AuditEvent::getAction, action);
        }
    }

    public record AuditItem(Long id, Long actorId, String actorName, String action,
                            String targetType, String targetId, String result,
                            String detail, LocalDateTime createdAt) {

        static AuditItem of(AuditEvent e) {
            return new AuditItem(e.getId(), e.getActorId(), e.getActorName(), e.getAction(),
                    e.getTargetType(), e.getTargetId(), e.getResult(), e.getDetail(), e.getCreatedAt());
        }
    }

    public record AuditPage(List<AuditItem> items, long total, int page, int size) {
    }

    // ===== 查询 =====

    public AuditPage page(AuditQuery query, int page, int size) {
        requireSystemAdmin();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        List<AuditItem> items = auditMapper.selectList(query.toWrapper()
                        .orderByDesc(AuditEvent::getId)
                        .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize))
                .stream().map(AuditItem::of).toList();
        long total = auditMapper.selectCount(query.toWrapper());
        return new AuditPage(items, total, safePage, safeSize);
    }

    /**
     * CSV 流式导出：keyset 分批拉取，逐行写出。表头固定；字段含逗号/引号/换行按 RFC 4180
     * 转义，公式前缀（=/+/-/@）加单引号防 CSV 注入（表格软件打开时的公式执行）。
     *
     * @return 实际导出行数
     */
    public long exportCsv(AuditQuery query, Writer out) throws IOException {
        requireSystemAdmin();
        out.write("id,createdAt,actorId,actorName,action,targetType,targetId,result,detail\n");
        long lastId = 0;
        long rows = 0;
        while (rows < EXPORT_MAX_ROWS) {
            List<AuditEvent> batch = auditMapper.selectList(query.toWrapper()
                    .gt(AuditEvent::getId, lastId)
                    .orderByAsc(AuditEvent::getId)
                    .last("LIMIT " + EXPORT_BATCH));
            if (batch.isEmpty()) {
                break;
            }
            for (AuditEvent e : batch) {
                out.write(csvLine(e));
                lastId = e.getId();
                rows++;
            }
            if (batch.size() < EXPORT_BATCH) {
                break;
            }
        }
        if (rows >= EXPORT_MAX_ROWS) {
            log.warn("审计导出达单次上限 {} 行，已截断（actor={}）", EXPORT_MAX_ROWS,
                    AuthContext.currentOrNull() == null ? null : AuthContext.currentOrNull().id());
        }
        return rows;
    }

    private CurrentUser requireSystemAdmin() {
        CurrentUser user = AuthContext.current();
        if (!user.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员可访问审计");
        }
        return user;
    }

    private String csvLine(AuditEvent e) {
        return String.join(",",
                csv(String.valueOf(e.getId())),
                csv(e.getCreatedAt() == null ? "" : CSV_TIME.format(e.getCreatedAt())),
                csv(e.getActorId() == null ? "" : String.valueOf(e.getActorId())),
                csv(e.getActorName()),
                csv(e.getAction()),
                csv(e.getTargetType()),
                csv(e.getTargetId()),
                csv(e.getResult()),
                csv(e.getDetail())) + "\n";
    }

    private static String csv(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "+-=@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
