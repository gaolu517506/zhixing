package com.zhixing.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp 全局单例：连接池/线程池全项目复用。每请求 new 一个 = 每次重新握手，延迟和 fd 双输。
 * readTimeout 在流式场景下的语义是"两个 chunk 之间的最大间隔"，不是总时长——总时长由上层控。
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
}
