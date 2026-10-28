package com.zhixing.config;

import com.zhixing.service.AuditRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审计兜底装配（v2.0 线 B 临时位）：T6-0 契约只有 AuditRecorder 接口，实现归线 A（T6-1）。
 * 线 A 实现合入前，token 发放/吊销链路需要一个 AuditRecorder bean 才能装配——本 no-op 让
 * 应用可启动、审计事件暂不落库；线 A 实现（组件扫描注册）出现后本 bean 自动让位。
 * 若线 A 合入时与本配置冲突，删除本类即可（收工报告 T6B §已注明）。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class AuditFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(AuditRecorder.class)
    public AuditRecorder noopAuditRecorder() {
        log.info("AuditRecorder 实现未合入（线 A/T6-1），启用 no-op 兜底：token 审计事件暂不落库");
        return (actorId, actorName, action, targetType, targetId, result, detailJson) -> {
            // 线 A 合入前的临时空实现；合入后 @ConditionalOnMissingBean 自动退位
        };
    }
}
