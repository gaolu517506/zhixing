package com.zhixing.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.dao.entity.TraceNode;
import com.zhixing.dao.entity.TraceRun;
import com.zhixing.dao.mapper.TraceNodeMapper;
import com.zhixing.dao.mapper.TraceRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TraceController MockMvc 离线测试（任务书 §2）：列表分页/bizType 过滤/详情 seq 排序与
 * 200 字预览截断/404。真库播种唯一 id 并在 @AfterEach 清理，不依赖既有数据形态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TraceRunMapper runMapper;

    @Autowired
    private TraceNodeMapper nodeMapper;

    private long qaRunId;

    private long ingestRunId;

    private final List<Long> seededRunIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        qaRunId = seedRun("QA", 3210L, "COMPLETED", null);
        node(qaRunId, "vector_search", 1, "x".repeat(2500));
        node(qaRunId, "llm_chat", 2, "ok");
        ingestRunId = seedRun("INGEST", 3211L, "FAILED", "embed 401");
    }

    @AfterEach
    void cleanup() {
        for (Long runId : seededRunIds) {
            nodeMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TraceNode>()
                    .eq(TraceNode::getRunId, runId));
            runMapper.deleteById(runId);
        }
    }

    private long seedRun(String bizType, long bizId, String status, String error) {
        TraceRun run = new TraceRun();
        run.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
        run.setBizType(bizType);
        run.setBizId(bizId);
        run.setStatus(status);
        run.setErrorMsg(error);
        run.setTotalCostMs(123L);
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        seededRunIds.add(run.getId());
        return run.getId();
    }

    private void node(long runId, String name, int seq, String input) {
        TraceNode n = new TraceNode();
        n.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
        n.setRunId(runId);
        n.setName(name);
        n.setSeq(seq);
        n.setCostMs(5L);
        n.setInput(input);
        n.setOutput("output-" + seq);
        n.setStatus("SUCCESS");
        n.setCreatedAt(LocalDateTime.now());
        nodeMapper.insert(n);
    }

    @Test
    void 列表_bizType过滤且包含播种run_id为字符串() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/trace/runs")
                        .param("bizType", "QA").param("page", "1").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("items");
        assertThat(items.size()).isGreaterThan(0);
        for (JsonNode item : items) {
            assertThat(item.path("bizType").asText()).isEqualTo("QA");
        }
        // Long→String 全局序列化：HTTP 层 id 必须是字符串（JS 53 位精度坑的验收点）
        List<String> ids = new ArrayList<>();
        items.forEach(n -> ids.add(n.path("id").asText()));
        assertThat(ids).contains(String.valueOf(qaRunId));
    }

    @Test
    void 列表_分页size生效() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/trace/runs")
                        .param("bizType", "INGEST").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);
        // 倒序：单页取到的是 INGEST 过滤结果里最新一条
        assertThat(page.path("items").get(0).path("bizType").asText()).isEqualTo("INGEST");
    }

    @Test
    void 详情_nodes按seq排序且input截断200字符() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/trace/runs/" + qaRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.id").value(String.valueOf(qaRunId)))
                .andExpect(jsonPath("$.data.run.bizId").value(3210))
                .andReturn();
        JsonNode nodes = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("nodes");
        assertThat(nodes.size()).isEqualTo(2);
        assertThat(nodes.get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(nodes.get(1).path("seq").asInt()).isEqualTo(2);
        String preview = nodes.get(0).path("input").asText();
        assertThat(preview).startsWith("x".repeat(200));
        assertThat(preview).endsWith("…(truncated)");
        assertThat(preview).hasSize(200 + "…(truncated)".length());
        assertThat(nodes.get(1).path("input").asText()).isEqualTo("ok");
    }

    @Test
    void 详情不存在_返回A0000业务码() throws Exception {
        mockMvc.perform(get("/api/trace/runs/999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0000"))
                .andExpect(jsonPath("$.message").value("Trace run 不存在：999999999"));
    }
}
