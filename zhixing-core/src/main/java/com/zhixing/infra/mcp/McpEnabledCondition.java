package com.zhixing.infra.mcp;

import com.zhixing.config.AgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * zhixing.agent.mcp.enabled=true 才装配 MCP 组件（任务书 §0：关闭态零行为——
 * 不启动子进程、不注册任何 bean）。默认 false。
 */
public class McpEnabledCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String enabled = context.getEnvironment().getProperty("zhixing.agent.mcp.enabled");
        boolean match = "true".equalsIgnoreCase(enabled);
        return new ConditionOutcome(match, "zhixing.agent.mcp.enabled=" + enabled);
    }
}
