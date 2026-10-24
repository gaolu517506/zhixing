package com.zhixing.infra.sse;

import java.io.IOException;

/**
 * SSE 事件出口的最小抽象（包结构治理：从 service 下沉 infra/sse——HTTP 推流属技术设施，
 * service/agent 编排层面向本接口，方向符合「上层依赖下层」）。
 * QaService 只面向它编排，测试用记录实现断言事件序，
 * 生产实现包 SseEmitter（HTTP 语义不进服务层单测）。
 */
public interface SseEventSink {

    /**
     * 发送一个事件。实现方需自行容忍客户端断开（断开只停外发，不中断服务端链路）。
     */
    void send(String event, Object data) throws IOException;

    /** 收尾路径专用：complete 前的最后事件（error/done）发送失败也不该中断收工。 */
    default void trySend(String event, Object data) {
        try {
            send(event, data);
        } catch (Exception ignored) {
            // 收尾事件发不出去（客户端已断开）不是业务失败
        }
    }

    /** 正常收尾。 */
    void complete();
}
