package com.zhixing.infra.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * McpToolAdapter 映射与降级测试（任务书 §4）：对桩 McpClient 验证
 * name/description/schema 透传（无前缀改造）、execute→tools/call 结果文本、isError 降级、
 * 连接异常传播（由 ToolExecutor 统一转错误结果——与本地工具同一约定）。
 */
class McpToolAdapterTest {

    private McpClient mcpClient;

    private McpToolAdapter adapter;

    private static McpSchema.Tool remoteTool(String name, String description) {
        return new McpSchema.Tool(name, null, description,
                Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))),
                null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        mcpClient = mock(McpClient.class);
        adapter = new McpToolAdapter(mcpClient, new ObjectMapper());
    }

    @Test
    void 适配透传_名称无前缀改造_schema转文本() throws Exception {
        when(mcpClient.listTools()).thenReturn(
                List.of(remoteTool("remote_echo", "远端回声工具")));

        List<Tool> tools = adapter.adaptAll();

        assertThat(tools).hasSize(1);
        Tool tool = tools.get(0);
        assertThat(tool.name()).isEqualTo("remote_echo");            // 原样，无前缀改造
        assertThat(tool.description()).isEqualTo("远端回声工具");      // 原样透传
        assertThat(tool.parametersSchema()).contains("\"message\"").contains("\"type\"");
    }

    @Test
    void execute_成功结果映射ToolResult_ok() {
        when(mcpClient.listTools()).thenReturn(List.of(remoteTool("remote_echo", "d")));
        when(mcpClient.callTool(eq("remote_echo"), anyMap()))
                .thenReturn(new McpClient.McpToolCallResult("远端结果文本", false));
        Tool tool = adapter.adaptAll().get(0);

        ToolResult result = tool.execute(Map.of("message", "hi"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("远端结果文本");
    }

    @Test
    void execute_isError降级为ToolResult_fail() {
        when(mcpClient.listTools()).thenReturn(List.of(remoteTool("remote_echo", "d")));
        when(mcpClient.callTool(any(), anyMap()))
                .thenReturn(new McpClient.McpToolCallResult("远端业务失败说明", true));
        Tool tool = adapter.adaptAll().get(0);

        ToolResult result = tool.execute(Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.content()).isEqualTo("远端业务失败说明");
    }

    @Test
    void execute_连接异常向上传播_由ToolExecutor统一转错误() {
        when(mcpClient.listTools()).thenReturn(List.of(remoteTool("remote_echo", "d")));
        when(mcpClient.callTool(any(), anyMap())).thenThrow(new McpClient.McpUnavailableException("重连耗尽", null));
        Tool tool = adapter.adaptAll().get(0);

        // 与本地工具同一约定：实现方不自行捕获——ToolExecutor 统一转错误结果回填
        assertThatThrownBy(() -> tool.execute(Map.of()))
                .isInstanceOf(McpClient.McpUnavailableException.class);
    }

    @Test
    void requireConfirm_恒false_远程写确认留v1点1() {
        when(mcpClient.listTools()).thenReturn(List.of(
                remoteTool("remote_echo", "d"),
                remoteTool("remote_delete_something", "疑似写操作")));
        List<Tool> tools = adapter.adaptAll();

        assertThat(tools).allSatisfy(tool -> assertThat(tool.requireConfirm()).isFalse());
    }

    @Test
    void inputSchema为空Map_原样透传() {
        when(mcpClient.listTools()).thenReturn(List.of(
                new McpSchema.Tool("empty_schema_tool", null, "空 schema", Map.of(), null, null, null, null)));

        Tool tool = adapter.adaptAll().get(0);

        // SDK Tool 构造器强制 inputSchema 非空；空对象 schema 原样透传（序列化为 {}）
        assertThat(tool.parametersSchema()).isEqualTo("{}");
    }
}
