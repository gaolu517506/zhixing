package com.zhixing.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhixing.config.MemoryProperties;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.entity.QaSession;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话记忆（详设 08-阶段2 §5）：滚动摘要 + 最近 N 轮完整对话的窗口视图。
 *
 * <p>窗口规则：轮 = user + assistant 成对；窗口截取按完整对收集、偶数对齐——
 * 尾部只有 user 没有 assistant 的半轮属于"正在进行的对话"，不进 window
 * （它的 assistant 还没产生，进窗口只会让下一条消息角色交替错位）。
 *
 * <p>查询策略：尾部 2N+1 条足以判定窗口（N 对 + 可能的尾部半轮 1 条）；
 * 窗口外的更早轮次只在摘要触发时经 {@link #turnsBeforeWindow} 按需查询，不随每次问答全量加载。
 */
@Component
public class SessionMemory {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final QaSessionMapper sessionMapper;

    private final QaMessageMapper messageMapper;

    private final MemoryProperties props;

    public SessionMemory(QaSessionMapper sessionMapper, QaMessageMapper messageMapper, MemoryProperties props) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.props = props;
    }

    /** summary = qa_session.summary_text（可 null）；window = 最近 N 轮完整对话（升序）。 */
    public MemoryView load(long sessionId) {
        QaSession session = sessionMapper.selectById(sessionId);
        String summary = session == null ? null : session.getSummaryText();
        return new MemoryView(summary, slice(sessionId).window());
    }

    /** 已完成的完整轮数 = assistant 消息计数（窗口外的也计入——摘要触发条件用它）。 */
    public int completedTurns(long sessionId) {
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getSessionId, sessionId)
                .eq(QaMessage::getRole, ROLE_ASSISTANT));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 窗口之外的更早完整轮次（升序）。摘要触发时由 QaService 取用（素材策略：最早 4 轮增量）。
     * 窗口不满 N 轮时所有完整轮都在窗口内，本方法返回空列表。
     */
    public List<Turn> turnsBeforeWindow(long sessionId) {
        Long boundaryId = slice(sessionId).boundaryId();
        if (boundaryId == null) {
            return List.of();
        }
        List<QaMessage> earlier = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getSessionId, sessionId)
                .lt(QaMessage::getId, boundaryId)
                .orderByAsc(QaMessage::getId));
        return pairAll(earlier);
    }

    private record Slice(Long boundaryId, List<Turn> window) {
    }

    /**
     * 窗口切片：取尾部 2N+1 条，从尾部按 (user, assistant) 完整对向前收集至多 N 对。
     * boundaryId = 窗口最早一条 user 消息的 id（其之前即"窗口外更早轮次"）；
     * 窗口未满（会话轮数 < N）时 boundaryId 为 null。
     */
    private Slice slice(long sessionId) {
        int n = props.getWindowSize();
        List<QaMessage> tail = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getSessionId, sessionId)
                .orderByDesc(QaMessage::getId)
                .last("LIMIT " + (2L * n + 1)));
        List<QaMessage> asc = new ArrayList<>(tail);
        Collections.reverse(asc);

        List<Turn> window = new ArrayList<>();
        Long boundaryId = null;
        int i = asc.size() - 1;
        // 尾部半轮：最后一条是 user（assistant 还没落库）→ 不进窗口
        if (i >= 0 && isUser(asc.get(i))) {
            i--;
        }
        while (i >= 1 && window.size() < n * 2) {
            QaMessage assistant = asc.get(i);
            QaMessage user = asc.get(i - 1);
            if (!isAssistant(assistant) || !isUser(user)) {
                break;
            }
            window.add(0, new Turn(ROLE_ASSISTANT, assistant.getContent()));
            window.add(0, new Turn(ROLE_USER, user.getContent()));
            boundaryId = user.getId();
            i -= 2;
        }
        return new Slice(boundaryId, List.copyOf(window));
    }

    /** 全量升序消息按位配对（写入路径保证 user 先于 assistant，防御性校验首元素）。 */
    private static List<Turn> pairAll(List<QaMessage> msgs) {
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i + 1 < msgs.size(); i += 2) {
            if (!isUser(msgs.get(i)) || !isAssistant(msgs.get(i + 1))) {
                break;
            }
            turns.add(new Turn(ROLE_USER, msgs.get(i).getContent()));
            turns.add(new Turn(ROLE_ASSISTANT, msgs.get(i + 1).getContent()));
        }
        return List.copyOf(turns);
    }

    private static boolean isUser(QaMessage m) {
        return ROLE_USER.equals(m.getRole());
    }

    private static boolean isAssistant(QaMessage m) {
        return ROLE_ASSISTANT.equals(m.getRole());
    }
}
