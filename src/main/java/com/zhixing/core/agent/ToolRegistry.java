package com.zhixing.core.agent;

import com.zhixing.infra.llm.dto.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册表（详设 13-阶段3 §6）：收集全部 Tool bean 自动注册；生成 ChatRequest.tools 声明、
 * 按名查找。A 线不实现具体工具（B 线的三个工具在 service/agenttools）——
 * 本分支上无 Tool bean 时注册表为空，引擎等价于无工具对话，功能不破。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> toolBeans) {
        this.tools = toolBeans.stream()
                .collect(Collectors.toUnmodifiableMap(Tool::name, t -> t));
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
