package com.zhixing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.common.BusinessException;
import com.zhixing.common.ErrorCode;
import com.zhixing.common.auth.AuthContext;
import com.zhixing.common.auth.CurrentUser;
import com.zhixing.dao.entity.QaSession;
import com.zhixing.dao.entity.TraceNode;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.QaSessionMapper;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trace 观测查询（02-技术方案 §B2 调用点清单）：run 详情按归属收敛——本人（qa_session.user_id
 * 归属）或系统 ADMIN 可见，非归属人统一 A0006（不泄露他人 run 的存在性）。
 * 列表仍为登录用户全量（任务书 §①仅约束详情；管理视图由线 3/B7 演进）。
 */
@Service
public class TraceService {

    private final TraceRunMapper runMapper;

    private final TraceNodeMapper nodeMapper;

    private final QaSessionMapper sessionMapper;

    public TraceService(TraceRunMapper runMapper, TraceNodeMapper nodeMapper,
                        QaSessionMapper sessionMapper) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
        this.sessionMapper = sessionMapper;
    }

    public TraceRunDetail getRunDetail(long id) {
        TraceRun run = runMapper.selectById(id);
        if (run == null) {
            throw new BusinessException(ErrorCode.CLIENT_ERROR, "Trace run 不存在：" + id);
        }
        CurrentUser user = AuthContext.current();
        if (!user.isAdmin()) {
            QaSession session = run.getBizId() == null ? null : sessionMapper.selectById(run.getBizId());
            if (session == null || session.getUserId() == null || !user.id().equals(session.getUserId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "仅运行归属人可查看该 Trace");
            }
        }
        List<TraceNodeItem> nodes = nodeMapper.selectList(new LambdaQueryWrapper<TraceNode>()
                        .eq(TraceNode::getRunId, id)
                        .orderByAsc(TraceNode::getSeq))
                .stream().map(TraceNodeItem::of).toList();
        return new TraceRunDetail(TraceRunItem.of(run), nodes);
    }

    // ===== 视图（自 TraceController 下沉，协议不变） =====

    /** 详情页 input/output 展示截断长度（落库上限 2000，前端只需预览）。 */
    private static final int NODE_TEXT_PREVIEW = 200;

    private static final String TRUNCATED_SUFFIX = "…(truncated)";

    public record TraceRunItem(Long id, String bizType, Long bizId, String status,
                               Long totalCostMs, String errorMsg, LocalDateTime createdAt) {

        public static TraceRunItem of(TraceRun run) {
            return new TraceRunItem(run.getId(), run.getBizType(), run.getBizId(), run.getStatus(),
                    run.getTotalCostMs(), run.getErrorMsg(), run.getCreatedAt());
        }
    }

    public record TraceNodeItem(String name, Integer seq, Long costMs, String status,
                                String error, String input, String output) {

        static TraceNodeItem of(TraceNode node) {
            return new TraceNodeItem(node.getName(), node.getSeq(), node.getCostMs(), node.getStatus(),
                    node.getError(), preview(node.getInput()), preview(node.getOutput()));
        }
    }

    public record TraceRunDetail(TraceRunItem run, List<TraceNodeItem> nodes) {
    }

    private static String preview(String text) {
        if (text == null || text.length() <= NODE_TEXT_PREVIEW) {
            return text;
        }
        return text.substring(0, NODE_TEXT_PREVIEW) + TRUNCATED_SUFFIX;
    }
}
