package com.zhixing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 线程池装配（阶段 3 契约基线）。
 * agent-pool 与 qa-pool 隔离：Agent 链路单请求占线程更久（多轮工具执行），
 * 混池会相互饿死——隔离理由同 retrieval-pool 与 qa-pool 的关系。
 */
@Configuration
@EnableConfigurationProperties({AgentProperties.class, CircuitProperties.class})
public class AgentPoolConfig {

    @Bean(name = "agent-pool")
    public ThreadPoolExecutor agentPool(AgentProperties props) {
        return new ThreadPoolExecutor(2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new CustomizableThreadFactory("agent-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
