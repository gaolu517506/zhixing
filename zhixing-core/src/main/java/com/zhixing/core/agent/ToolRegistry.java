package com.zhixing.core.agent;

import com.zhixing.infra.llm.dto.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（详设 13-阶段3 §6）：收集全部 Tool bean 自动注册；生成 ChatRequest.tools 声明、
 * 按名查找。A 线不实现具体工具（B 线的三个工具在 service/agenttools）——
 * 本分支上无 Tool bean 时注册表为空，引擎等价于无工具对话，功能不破。
 *
 * <p>v1.0 扩展：registerAll 支持运行期动态注册（MCP 远程工具在 ApplicationReadyEvent 时经
 * McpBootstrap 调用）；与本地 bean 工具重名时**本地优先**（本地实现可控、远程不可信）。
 * ConcurrentHashMap：注册发生在启动期，并发查询安全。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        toolBeans.forEach(t -> tools.put(t.name(), t));
    }

    /** 动态注册（MCP 等远程工具）：重名时本地优先，跳过并告警。 */
    public void registerAll(List<Tool> external) {
        for (Tool tool : external) {
            Tool existing = tools.putIfAbsent(tool.name(), tool);
            if (existing != null) {
                log.warn("[registry] 工具 {} 与本地实现重名，本地优先，远程注册跳过", tool.name());
            }
        }
    }

    /** Function Calling 的 tools 声明（OpenAI 协议格式由请求序列化层拼装）。 */
    public List<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(t -> new ToolDefinition(t.name(), t.description(), t.parametersSchema()))
                .toList();
    }

    /** 按名查找；未知名返回 null（引擎以错误结果回填让模型自纠，不终态）。 */
    public Tool find(String name) {
        return tools.get(name);
    }

    /** 全部工具名（未知工具错误回填时向模型展示可用集）。 */
    public String names() {
        return String.join(", ", tools.keySet());
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }
}
