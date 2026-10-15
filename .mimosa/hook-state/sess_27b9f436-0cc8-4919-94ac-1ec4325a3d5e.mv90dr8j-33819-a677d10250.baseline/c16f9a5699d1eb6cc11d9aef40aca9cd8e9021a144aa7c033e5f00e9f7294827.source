package com.zhixing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 检索问答线线程池装配。两个池严格隔离：
 * qa-pool 跑整条 QA 链路（会阻塞在 LLM 流上，一请求占一线程）；
 * retrieval-pool 只跑检索双通道（毫秒级任务）。混用一个池会嵌套提交——
 * 链路任务占满线程时通道任务永远排队，join 永不返回，整池死锁。
 */
@Configuration
@EnableConfigurationProperties({RetrieveProperties.class, QaProperties.class, EvalProperties.class, TraceProperties.class})
public class RetrievalPoolConfig {

    @Bean(name = "retrieval-pool")
    public ThreadPoolExecutor retrievalPool(RetrieveProperties props) {
        return new ThreadPoolExecutor(props.getPoolCore(), props.getPoolMax(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new CustomizableThreadFactory("retrieval-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "qa-pool")
    public ThreadPoolExecutor qaPool(QaProperties props) {
        return new ThreadPoolExecutor(props.getPoolCore(), props.getPoolMax(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new CustomizableThreadFactory("qa-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
