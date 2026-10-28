package com.zhixing.service;

import cn.hutool.core.util.IdUtil;
import com.zhixing.dao.entity.AuditEvent;
import com.zhixing.dao.mapper.AuditEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计记录器实现（02-技术方案 §B3，D15 显式调用）。唯一写入口、仅 insert——
 * 落库失败只 warn 绝不抛出：审计是主链路的旁路观察者，DB 抖动不允许拦停
 * 登录/建库/删除等业务动作（契约测试：mock 落库异常时主链路无感知）。
 */
@Slf4j
@Service
public class AuditRecorderImpl implements AuditRecorder {

    private final AuditEventMapper auditMapper;

    public AuditRecorderImpl(AuditEventMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    @Override
    public void record(Long actorId, String actorName, String action,
                       String targetType, String targetId, String result, String detailJson) {
        try {
            AuditEvent event = new AuditEvent();
            event.setId(IdUtil.getSnowflakeNextId());
            event.setActorId(actorId);
            event.setActorName(actorName);
            event.setAction(action);
            event.setTargetType(targetType);
            event.setTargetId(targetId);
            event.setResult(result);
            event.setDetail(detailJson);
            event.setCreatedAt(LocalDateTime.now());
            auditMapper.insert(event);
        } catch (Exception e) {
            log.warn("审计事件落库失败（不阻塞主链路）action={} target={}/{}: {}",
                    action, targetType, targetId, e.getMessage());
        }
    }
}
