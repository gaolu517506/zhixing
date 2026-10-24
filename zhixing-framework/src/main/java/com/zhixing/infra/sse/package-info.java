/**
 * 技术设施 · SSE 推流：SseEventSink 契约 → SseEmitterSink 安全实现。
 *
 * <p>语义红线：客户端断开只停外发（clientGone 置位），服务端链路继续生成、继续落库——
 * 终态成对规则（概设 §3.4）在服务端永远走完。
 * 包结构治理：从 service 下沉至此，QaService 与阶段 3 AgentService 共用。
 */
package com.zhixing.infra.sse;
