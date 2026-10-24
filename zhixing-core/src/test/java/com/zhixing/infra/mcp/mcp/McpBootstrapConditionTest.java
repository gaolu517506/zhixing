package com.zhixing.infra.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhixing.config.AgentProperties;
import com.zhixing.core.agent.Tool;
import com.zhixing.core.agent.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条件装配测试（任务书 §3.4）：enabled=false（默认）→ McpBootstrap bean 不存在（零行为）；
 * enabled=true → bean 存在。ApplicationReadyEvent 在测试环境不触发——bean 创建本身
 * 不启动子进程（就绪前零行为由此保证）。
 */
class McpBootstrapConditionTest {

    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(McpBootstrap.class, TestBeans.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class TestBeans {
        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of());
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void 默认关闭_零行为_无McpBootstrapBean() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(McpBootstrap.class);
            assertThat(context).doesNotHaveBean(McpClient.class);
        });
    }

    @Test
    void 显式false同样零行为() {
        runner.withPropertyValues("zhixing.agent.mcp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(McpBootstrap.class));
    }

    @Test
    void enabled为true_bean存在但未启动子进程() {
        runner.withPropertyValues("zhixing.agent.mcp.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpBootstrap.class);
                    // ApplicationReadyEvent 未发布：不会走到子进程启动（零副作用）
                    assertThat(context).doesNotHaveBean(McpClient.class);
                });
    }
}
