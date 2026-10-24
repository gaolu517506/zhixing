/**
 * REST / SSE 接入层：参数校验、协议翻译（Result / SseEmitter）、异常翻译，不写业务。
 *
 * <p>内容：KbController、QaController、EvalController（评测旁路，条件装配）、
 * TraceController（观测）、HealthController、LlmExceptionHandler（ModelClientException→C0001）。
 *
 * <p>依赖规则：→ service → core → infra 单向向下；
 * 本层不出现业务判断与事务边界；SSE 事件协议（概设 §3.4）是前后端契约，本层不做语义变更。
 */
package com.zhixing.controller;
