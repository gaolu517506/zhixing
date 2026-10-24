package com.zhixing.infra.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SseEmitter 的安全包装（infra/sse 实现，配合 {@link SseEventSink} 契约）：
 * 客户端断连/超时后 send 会抛，这里吞掉并置位 clientGone，
 * 让上层链路"继续生成、继续落库、不再外发"——终态成对规则在服务端永远走完。
 * 生命周期回调（onTimeout/onError）统一在这里注册。
 */
@Slf4j
public class SseEmitterSink implements SseEventSink {

    private final SseEmitter emitter;

    private volatile boolean clientGone = false;

    public SseEmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onTimeout(this::onTransportGone);
        emitter.onError(t -> onTransportGone());
    }

    private void onTransportGone() {
        clientGone = true;
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("emitter complete on gone: {}", e.getMessage());
        }
    }

    @Override
    public void send(String event, Object data) throws IOException {
        if (clientGone) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IllegalStateException | IOException e) {
            // 断连后的 send 失败不是业务失败：置位并继续（链路照常落库收尾）
            clientGone = true;
            log.debug("sse send 失败（客户端大概率已断开），后续事件不再外发: {}", e.getMessage());
        }
    }

    /** 收尾路径专用：complete 本身失败也不该让收工逻辑中断。 */
    public void trySend(String event, Object data) {
        try {
            send(event, data);
        } catch (Exception e) {
            log.debug("sse trySend 被忽略: {}", e.getMessage());
        }
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("sse complete 被忽略: {}", e.getMessage());
        }
    }
}
