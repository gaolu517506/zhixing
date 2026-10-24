package com.zhixing.infra.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * MCP 远程工具 → core/agent Tool 契约的适配器（详设 14-v1.0 §4.2）。
 *
 * <p>映射规则：name/description 原样透传（不做前缀改造——重名冲突由 ToolRegistry 本地优先兜底）；
 * inputSchema（SDK 结构化 Map）序列化为 JSON Schema 文本透传；execute → tools/call →
 * content text 拼接为结果文本；requireConfirm 恒 false（远程写操作的确认策略留 v1.1 决策）。
 *
 * <p>失败语义：tools/call 的 isError=true → ToolResult.fail（回填模型自纠）；
 * McpClient 连接级异常向上传播——由 ToolExecutor 统一转错误结果（与本地工具同一约定）。
 */
@Slf4j
public class McpToolAdapter {

    private final McpClient mcpClient;

    private final ObjectMapper objectMapper;

    public McpToolAdapter(McpClient mcpClient, ObjectMapper objectMapper) {
        this.mcpClient = mcpClient;
        this.objectMapper = objectMapper;
    }

    /** 发现并适配全部远程工具。 */
    public List<Tool> adaptAll() {
        return adapt(mcpClient.listTools());
    }

    public List<Tool> adapt(List<McpSchema.Tool> remoteTools) {
        return remoteTools.stream().map(this::adapt).toList();
    }

    private Tool adapt(McpSchema.Tool remote) {
        log.info("[mcp] 适配远程工具：{}（{}）", remote.name(),
                remote.description() != null ? "有描述" : "无描述");
        String parametersSchema = serializeSchema(remote.name(), remote.inputSchema());
        return new Tool() {
            @Override
            public String name() {
                return remote.name();
            }

            @Override
            public String description() {
                return remote.description();
            }

            @Override
            public String parametersSchema() {
                return parametersSchema;
            }

            @Override
            public ToolResult execute(Map<String, Object> args) {
                McpClient.McpToolCallResult result = mcpClient.callTool(remote.name(), args);
                if (result.isError()) {
                    return ToolResult.fail(result.text(), result.text());
                }
                return ToolResult.ok(result.text());
            }

            @Override
            public boolean requireConfirm() {
                return false; // 远程写操作的确认策略留 v1.1 决策（详设 14-v1.0 §4.2）
            }
        };
    }

    /** inputSchema（Map）→ JSON Schema 文本；序列化失败回退为放行的空 schema（不让单个坏工具拖垮接入）。 */
    private String serializeSchema(String toolName, Map<String, Object> inputSchema) {
        try {
            return objectMapper.writeValueAsString(
                    inputSchema == null ? Map.of("type", "object", "properties", Map.of()) : inputSchema);
        } catch (Exception e) {
            log.warn("[mcp] 工具 {} 的 inputSchema 序列化失败，回退空 schema: {}", toolName, e.getMessage());
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }
}
