package com.zhixing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.RouteProperties;
import com.zhixing.dao.entity.IntentRoute;
import com.zhixing.dao.entity.KbKnowledgeBase;
import com.zhixing.dao.mapper.IntentRouteMapper;
import com.zhixing.dao.mapper.KbKnowledgeBaseMapper;
import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IntentRouter 三级路由单测（02 §B10 测试点，任务书 34-§1②）：
 * 关键词级取最优/权限交集/交集空进下一级、LLM 分类成功与失败、回落全集、开关关闭。
 */
class IntentRouterTest {

    private IntentRouteMapper routeMapper;

    private KbKnowledgeBaseMapper kbMapper;

    private ChatClient chatClient;

    private IntentRouter router;

    private static final List<Long> ACCESSIBLE = List.of(11L, 12L, 13L);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IntentRoute.class);
        TableInfoHelper.initTableInfo(assistant, KbKnowledgeBase.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        routeMapper = mock(IntentRouteMapper.class);
        kbMapper = mock(KbKnowledgeBaseMapper.class);
        chatClient = mock(ChatClient.class);
        router = new IntentRouter(routeMapper, kbMapper, chatClient, new RouteProperties(), new ObjectMapper());
        when(routeMapper.selectList(any())).thenReturn(List.of(
                route(201L, "保修咨询", "[\"保修\",\"质保\",\"售后\"]", "[11]"),
                route(202L, "故障排查", "[\"故障\",\"无法开机\",\"报错\"]", "[12,13]")));
    }

    private IntentRoute route(long id, String name, String keywords, String targets) {
        IntentRoute entity = new IntentRoute();
        entity.setId(id);
        entity.setName(name);
        entity.setKeywords(keywords);
        entity.setExamples("[]");
        entity.setTargetKbIds(targets);
        entity.setEnabled(true);
        return entity;
    }

    // ===== ① 关键词级 =====

    @Test
    void 关键词命中_多规则取命中词数最优_目标过权限交集() {
        // 「保修 + 售后」命中规则 201 两词；「故障」命中 202 一词 → 取 201
        IntentRouter.RouteDecision decision = router.route("保修和售后政策是什么", ACCESSIBLE);
        assertThat(decision.matched()).isEqualTo("KEYWORD");
        assertThat(decision.routeId()).isEqualTo(201L);
        assertThat(decision.score()).isEqualTo(2);
        assertThat(decision.routedKbIds()).containsExactly(11L);
    }

    @Test
    void 关键词命中但目标不在权限集_进下一级最终回落() {
        // 命中 201（目标 11）但 11 不在 accessible → 交集空 → LLM（未启用桩失败）→ 回落
        IntentRouter.RouteDecision decision = router.route("保修几年", List.of(12L, 13L));
        assertThat(decision.matched()).isEqualTo("FALLBACK");
        assertThat(decision.routedKbIds()).containsExactly(12L, 13L);
    }

    // ===== ② LLM 分类 =====

    @Test
    void 关键词未命中_LLM分类命中_目标过权限交集() {
        when(chatClient.call(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("故障排查", null, null));
        IntentRouter.RouteDecision decision = router.route("机器坏了怎么办", ACCESSIBLE);
        assertThat(decision.matched()).isEqualTo("LLM");
        assertThat(decision.routeId()).isEqualTo(202L);
        // 202 目标 [12,13] ∩ accessible [11,12,13] = [12,13]
        assertThat(decision.routedKbIds()).containsExactly(12L, 13L);
    }

    @Test
    void LLM失败_异常吞掉回落全集_永不失败() {
        when(chatClient.call(any(ChatRequest.class))).thenThrow(new RuntimeException("provider down"));
        IntentRouter.RouteDecision decision = router.route("完全无关的问题", ACCESSIBLE);
        assertThat(decision.matched()).isEqualTo("FALLBACK");
        assertThat(decision.routedKbIds()).containsExactlyInAnyOrderElementsOf(ACCESSIBLE);
    }

    @Test
    void LLM回复无法判断_回落() {
        when(chatClient.call(any(ChatRequest.class))).thenReturn(new ChatResponse("无", null, null));
        IntentRouter.RouteDecision decision = router.route("今天天气如何", ACCESSIBLE);
        assertThat(decision.matched()).isEqualTo("FALLBACK");
    }

    // ===== ③ 回落与开关 =====

    @Test
    void 路由开关关闭_永远回落且不查规则() {
        router = new IntentRouter(routeMapper, kbMapper, chatClient,
                new RouteProperties() {
                    {
                        setEnabled(false);
                    }
                }, new ObjectMapper());
        IntentRouter.RouteDecision decision = router.route("保修几年", ACCESSIBLE);
        assertThat(decision.matched()).isEqualTo("FALLBACK");
        assertThat(decision.routedKbIds()).containsExactlyInAnyOrderElementsOf(ACCESSIBLE);
        org.mockito.Mockito.verify(routeMapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    void llm级关闭_关键词未命中直接回落() {
        RouteProperties props = new RouteProperties();
        props.setLlmEnabled(false);
        router = new IntentRouter(routeMapper, kbMapper, chatClient, props, new ObjectMapper());
        IntentRouter.RouteDecision decision = router.route("完全无关的问题", ACCESSIBLE);
        assertThat(decision.matched()).isEqualTo("FALLBACK");
        org.mockito.Mockito.verifyNoInteractions(chatClient);
    }

    // ===== 试测面板 =====

    @Test
    void test_干跑_悬空目标库被过滤() {
        when(kbMapper.selectList(null)).thenReturn(List.of(kb(11L), kb(12L), kb(13L), kb(99L)));
        when(kbMapper.selectById(11L)).thenReturn(kb(11L)); // 99 无桩 = 悬空（selectById 默认 null）
        // 规则 201 目标 [11,99]：99 库不存在（悬空目标），试测面板应过滤
        when(routeMapper.selectList(any())).thenReturn(List.of(
                route(201L, "保修咨询", "[\"保修\"]", "[11,99]")));
        IntentRouter.RouteDecision decision = router.test("保修几年");
        assertThat(decision.matched()).isEqualTo("KEYWORD");
        assertThat(decision.routedKbIds()).containsExactly(11L);
    }

    private KbKnowledgeBase kb(long id) {
        KbKnowledgeBase entity = new KbKnowledgeBase();
        entity.setId(id);
        entity.setName("库" + id);
        return entity;
    }
}
