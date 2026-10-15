package com.zhixing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 入库异步线程池。队列有界（64）：积压超过容量后触发 CallerRunsPolicy，
 * 由提交线程（HTTP 线程）亲自执行——天然背压，入库速度被拖住时先拖住上传方，
 * 而不是无限堆任务撑爆内存。单用户规模下这一层足够，阶段 4 再谈公平排队。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class IngestPoolConfig {

    @Bean("ingest-pool")
    public ExecutorService ingestPool(@Value("${zhixing.ingest.pool-core:2}") int core,
                                      @Value("${zhixing.ingest.pool-max:4}") int max) {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(core, max, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "ingest-pool-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
