package com.zhixing.config;

import com.zhixing.infra.llm.ChatClient;
import com.zhixing.infra.llm.ResilientChatDecorator;
import com.zhixing.infra.llm.DeepSeekChatClient;
import com.zhixing.infra.llm.ZhipuChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * 容错装配（详设 13-阶段3 §11）：ResilientChatDecorator 以 @Primary 接管 ChatClient 主路由，
 * 候选链按 yaml zhixing.llm.chat.{primary,fallback} 组装；QaService/AgentService 注入的仍是
 * ChatClient 接口，对容错零感知。LlmClientConfig.primaryChatClient 已按任务书授权移除 @Primary，
 * 降级为普通 bean（不再被注入消费，保留以兼容既有引用与阶段 4 前的行为对照）。
 *
 * <p>候选链直接注入两个具体供应商客户端（它们不依赖装饰器，无循环）；
 * 切勿把 ChatClient 接口类型注入装配方法——那会解析到装饰器自身，造成候选链自引用。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(CircuitProperties.class)
public class ResilientConfig {

    @Bean
    @Primary
    public ChatClient resilientChatClient(LlmProperties props,
                                          CircuitProperties circuitProps,
                                          DeepSeekChatClient deepSeekChatClient,
                                          ZhipuChatClient zhipuChatClient) {
        ChatClient primary = resolveRoute(props.getChat().getPrimary(), deepSeekChatClient, zhipuChatClient);
        String fallbackRoute = props.getChat().getFallback();
        List<ChatClient> candidates = isBlank(fallbackRoute)
                ? List.of(primary)
                : List.of(primary, resolveRoute(fallbackRoute, deepSeekChatClient, zhipuChatClient));

        log.info("[容错] 候选链组装完成：{} 个候选（first-token-timeout={}ms，熔断 {}/{}s/半开试探 {} 次）",
                candidates.size(), props.getChat().getFirstTokenTimeoutMs(),
                circuitProps.getFailureThreshold(), circuitProps.getOpenSeconds(),
                circuitProps.getHalfOpenAttempts());
        return new ResilientChatDecorator(candidates,
                props.getChat().getFirstTokenTimeoutMs(),
                circuitProps.getFailureThreshold(),
                circuitProps.getOpenSeconds(),
                circuitProps.getHalfOpenAttempts());
    }

    /** 路由串形如 "deepseek/deepseek-chat"，取 / 前的供应商标识解析到具体客户端。 */
    private static ChatClient resolveRoute(String route, DeepSeekChatClient deepSeek, ZhipuChatClient zhipu) {
        String provider = route.contains("/")
                ? route.substring(0, route.indexOf('/'))
                : route;
        return switch (provider) {
            case "deepseek" -> deepSeek;
            case "zhipu" -> zhipu;
            default -> throw new IllegalStateException("zhixing.llm.chat 路由指向未知供应商：" + route);
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
