package com.zhixing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆线程池装配（详设 14-v1.0 §3.4，DL-17 ①）。
 * memory-pool 跑回合级异步仲裁：仲裁是「增强非必需」——队列满时静默丢弃新任务
 * （DiscardPolicy），宁可漏一轮记忆也不反压到问答响应线程。
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryPoolConfig {

    @Bean(name = "memory-pool")
    public ThreadPoolExecutor memoryPool() {
        return new ThreadPoolExecutor(1, 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                new CustomizableThreadFactory("memory-"),
                new ThreadPoolExecutor.DiscardPolicy());
    }
}
