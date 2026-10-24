package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ToolCall;
import com.zhixing.infra.llm.dto.ToolCallDelta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tool_calls 增量聚合器单测（任务书 §1.3 六用例）：正常聚合 / 乱序 index / 中文与转义引号 /
 * 多工具交错 / id 缺失兜底 / 空聚合。离线纯内存。
 */
class ToolCallAggregatorTest {

    private static ToolCallDelta delta(int index, String id, String name, String fragment) {
        return new ToolCallDelta(index, id, name, fragment);
    }

    @Test
    void 分片正常聚合() {
        ToolCallAggregator aggregator = new ToolCallAggregator();
        aggregator.feed(delta(0, "call_a", "search_knowledge", "{\"qu"));
        aggregator.feed(delta(0, null, null, "ery\":\"小米15 Ultra 防水\"}"));

        List<ToolCall> snapshot = aggregator.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).id()).isEqualTo("call_a");
        assertThat(snapshot.get(0).name()).isEqualTo("search_knowledge");
        assertThat(snapshot.get(0).arguments()).isEqualTo("{\"query\":\"小米15 Ultra 防水\"}");
    }

    @Test
    void 乱序index_快照按index升序() {
        ToolCallAggregator aggregator = new ToolCallAggregator();
        aggregator.feed(delta(1, "call_b", "get_current_time", "{}"));
        aggregator.feed(delta(0, "call_a", "search_knowledge", "{\"query\":\"x\"}"));

        List<ToolCall> snapshot = aggregator.snapshot();
        assertThat(snapshot).extracting(ToolCall::id).containsExactly("call_a", "call_b");
    }

    @Test
    void arguments含中文与转义引号_逐片无损拼接() {
        ToolCallAggregator aggregator = new ToolCallAggregator();
        String frag1 = "{\"query\":\"说\\\"你好\\\"（中文）——";
        String frag2 = "转义\\\"引号\\\"\"}";
        aggregator.feed(delta(0, "call_x", "search_knowledge", frag1));
        aggregator.feed(delta(0, null, null, frag2));

        String arguments = aggregator.snapshot().get(0).arguments();
        // 聚合只做拼接不做任何转义改写——解析交给上层 JSON
        assertThat(arguments).isEqualTo(frag1 + frag2);
        assertThat(arguments).contains("中文");
    }

    @Test
    void 多工具交错分片_各自完整聚合() {
        ToolCallAggregator aggregator = new ToolCallAggregator();
        aggregator.feed(delta(1, "call_b", "get_current_time", "{}"));
        aggregator.feed(delta(0, "call_a", "search_knowledge", "{\"qu"));
        aggregator.feed(delta(1, null, null, null));
        aggregator.feed(delta(0, null, null, "ery\":1}"));

        List<ToolCall> snapshot = aggregator.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).id()).isEqualTo("call_a");
        assertThat(snapshot.get(0).arguments()).isEqualTo("{\"query\":1}");
        assertThat(snapshot.get(1).id()).isEqualTo("call_b");
        assertThat(snapshot.get(1).name()).isEqualTo("get_current_time");
        assertThat(snapshot.get(1).arguments()).isEqualTo("{}");
    }

    @Test
    void id缺失_以call_index兜底() {
        ToolCallAggregator aggregator = new ToolCallAggregator();
        aggregator.feed(delta(2, null, "get_current_time", "{}"));

        ToolCall call = aggregator.snapshot().get(0);
        assertThat(call.id()).isEqualTo("call_2");
        assertThat(call.name()).isEqualTo("get_current_time");
    }

    @Test
    void 空聚合_snapshot为空列表() {
        assertThat(new ToolCallAggregator().snapshot()).isEmpty();
    }
}
