package com.zhixing.infra.mcp;

import com.zhixing.config.AgentProperties;
import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 启动引导（详设 14-v1.0 §4.3）：ApplicationReadyEvent 时启动子进程 → 发现远程工具 →
 * 经 ToolRegistry.registerAll 注册（重名本地优先）。
 *
 * <p>降级语义（红线）：仅当 zhixing.agent.mcp.enabled=true 时本 bean 才存在（条件装配，
 * 关闭态零行为）；enabled 但连接失败 → warn 降级不终态，Agent 无 MCP 工具照常工作。
 *
 * <p>依赖前置：stdio 脚本引用的 npm 依赖未安装（node_modules 缺失）时，子进程启动即失败，
 * 走同一降级路径（mcp-server-demo/README 有安装说明）。
 */
@Slf4j
@Component
@Conditional(McpEnabledCondition.class)
public class McpBootstrap implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private final ToolRegistry toolRegistry;

    private final AgentProperties agentProperties;

    private final ObjectMapper objectMapper;

    private McpClient mcpClient;

    public McpBootstrap(ToolRegistry toolRegistry, AgentProperties agentProperties, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        AgentProperties.Mcp mcp = agentProperties.getMcp();
        log.info("[mcp] 启动 MCP 接入：{} {}", mcp.getCommand(), mcp.getArgs());
        try {
            mcpClient = new McpClient(mcp.getCommand(), List.of(mcp.getArgs().split("\\s+")), 500);
            mcpClient.start();
            McpToolAdapter adapter = new McpToolAdapter(mcpClient, objectMapper);
            List<Tool> tools = adapter.adaptAll();
            toolRegistry.registerAll(tools);
            log.info("[mcp] 接入完成：注册 {} 个远程工具", tools.size());
        } catch (Exception e) {
            // 降级不终态：清理会话，Agent 无 MCP 工具照常工作
            if (mcpClient != null) {
                mcpClient.close();
                mcpClient = null;
            }
            log.warn("[mcp] MCP 接入失败，已降级（Agent 无 MCP 工具照常工作）：{}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (mcpClient != null) {
            mcpClient.close(); // 销毁子进程，防僵尸
            mcpClient = null;
        }
    }
}
