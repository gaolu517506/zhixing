package com.zhixing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 评估执行池（F6 验收向导，D20「编排复用内核」）：评测逐题检索会长时间占用检索线程，
 * 必须与 retrieval-pool 隔离，避免评测饿死在线问答；池容量保守（评测是低频重操作）。
 */
@Configuration
public class EvalPoolConfig {

    @Bean(name = "eval-pool")
    public ThreadPoolExecutor evalPool(EvalProperties props) {
        return new ThreadPoolExecutor(props.getPoolCore(), props.getPoolMax(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                new CustomizableThreadFactory("eval-run-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
