/**
 * 技术设施 · 链路追踪（决策 D7）：TraceRecorder 手动埋点（try-finally 语义封装），不上 AOP/OTel。
 *
 * <p>语义：node() 异常记 FAILED 后原样上抛（业务语义不变）；埋点自身失败只留日志不影响业务；
 * input/output 截断 max-text-length；trace 关闭时 runId=0 零开销。
 */
package com.zhixing.infra.trace;
