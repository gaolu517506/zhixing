package com.zhixing.core.memory;

import com.zhixing.config.MemoryProperties;
import com.zhixing.dao.entity.QaMessage;
import com.zhixing.dao.mapper.QaMessageMapper;
import com.zhixing.dao.mapper.QaSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SessionMemory 单测（任务书 §3）：Mockito 桩 mapper，离线。
 * 桩按 mapper 契约返回：selectList(orderByDesc + LIMIT) 返回 id 降序列表。
 */
class SessionMemoryTest {

    private QaSessionMapper sessionMapper;

    private QaMessageMapper messageMapper;

    private SessionMemory memory;

    /** 升序全量消息（id 从 1 起），供构造降序桩与断言窗口外轮次复用。 */
    private final List<QaMessage> asc = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sessionMapper = mock(QaSessionMapper.class);
        messageMapper = mock(QaMessageMapper.class);
        memory = new SessionMemory(sessionMapper, messageMapper, new MemoryProperties());
    }

    private static QaMessage msg(long id, String role, String content) {
        QaMessage m = new QaMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** 构造 turns 个完整轮（user=问题N / assistant=回答N），按 mapper 契约（降序）打桩。 */
    private void stubTurns(int turns) {
        long id = 1;
        for (int t = 1; t <= turns; t++) {
            asc.add(msg(id++, "user", "问题" + t));
            asc.add(msg(id++, "assistant", "回答" + t));
        }
        stubDesc();
    }

    private void stubDesc() {
        List<QaMessage> desc = new ArrayList<>(asc);
        Collections.reverse(desc);
        when(messageMapper.selectList(any())).thenReturn(desc);
    }

    @Test
    void 空会话_摘要与窗口均为空() {
        when(sessionMapper.selectById(1L)).thenReturn(null);
        when(messageMapper.selectList(any())).thenReturn(List.of());

        MemoryView view = memory.load(1L);

        assertThat(view.summary()).isNull();
        assertThat(view.window()).isEmpty();
    }

    @Test
    void 恰好8轮_全部进窗口且角色交替() {
        stubTurns(8);

        MemoryView view = memory.load(1L);

        assertThat(view.window()).hasSize(16);
        assertThat(view.window().get(0)).isEqualTo(new Turn("user", "问题1"));
        assertThat(view.window().get(1)).isEqualTo(new Turn("assistant", "回答1"));
        assertThat(view.window().get(15)).isEqualTo(new Turn("assistant", "回答8"));
        for (int i = 0; i < 16; i++) {
            assertThat(view.window().get(i).role()).isEqualTo(i % 2 == 0 ? "user" : "assistant");
        }
    }

    @Test
    void 十轮_截取最近八轮() {
        stubTurns(10);

        MemoryView view = memory.load(1L);

        assertThat(view.window()).hasSize(16);
        assertThat(view.window().get(0)).isEqualTo(new Turn("user", "问题3"));
        assertThat(view.window().get(15)).isEqualTo(new Turn("assistant", "回答10"));
        assertThat(view.window()).noneMatch(t -> t.content().equals("问题1") || t.content().equals("回答2"));
    }

    @Test
    void 尾部半轮丢弃_进行中的user不进窗口() {
        stubTurns(8);
        asc.add(msg(17L, "user", "进行中的问题"));
        stubDesc();

        MemoryView view = memory.load(1L);

        assertThat(view.window()).hasSize(16);
        assertThat(view.window()).noneMatch(t -> t.content().equals("进行中的问题"));
        assertThat(view.window().get(15)).isEqualTo(new Turn("assistant", "回答8"));
    }

    @Test
    void summary从会话行带出() {
        com.zhixing.dao.entity.QaSession session = new com.zhixing.dao.entity.QaSession();
        session.setId(1L);
        session.setSummaryText("旧摘要文本");
        when(sessionMapper.selectById(1L)).thenReturn(session);
        stubTurns(1);

        MemoryView view = memory.load(1L);

        assertThat(view.summary()).isEqualTo("旧摘要文本");
        assertThat(view.window()).hasSize(2);
    }

    @Test
    void completedTurns取assistant消息计数() {
        when(messageMapper.selectCount(any())).thenReturn(7L);

        assertThat(memory.completedTurns(1L)).isEqualTo(7);
    }

    @Test
    void turnsBeforeWindow返回窗口外更早完整轮次() {
        stubTurns(10);
        // 第二次查询（id < 窗口边界）：窗口首条 user 是"问题3"（id=5），更早为消息 1-4
        when(messageMapper.selectList(any())).thenReturn(
                new ArrayList<>(asc).subList(0, 4));

        List<Turn> earlier = memory.turnsBeforeWindow(1L);

        assertThat(earlier).hasSize(4);
        assertThat(earlier).extracting(Turn::content)
                .containsExactly("问题1", "回答1", "问题2", "回答2");
    }

    @Test
    void 窗口未满时无窗口外轮次() {
        stubTurns(3);

        assertThat(memory.turnsBeforeWindow(1L)).isEmpty();
    }
}
