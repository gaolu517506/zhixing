package com.zhixing.config;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp 全局单例：连接池/线程池全项目复用。每请求 new 一个 = 每次重新握手，延迟和 fd 双输。
 * readTimeout 在流式场景下的语义是"两个 chunk 之间的最大间隔"，不是总时长——总时长由上层控。
 *
 * <p>强制 HTTP/1.1（2026-10-15 集成验收发现，DL-13）：与 api.deepseek.com 的 h2 连接
 * 建立正常但流式响应零字节且 readTimeout 难触发（直连与经代理均复现），HTTP/1.1 实测正常——
 * SSE 长流式场景 1.1 行为更稳定、抓包更易排查，禁用 h2 协议协商。
 */
@Configuration
public class OkHttpConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .protocols(List.of(Protocol.HTTP_1_1))
                .build();
    }
}
