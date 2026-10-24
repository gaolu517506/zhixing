/**
 * 用例编排层：事务边界在此，面向 controller 暴露用例，面向 core/infra 组装链路。
 *
 * <p>内容：KbService（建库/上传/删除/级联）、QaService（RAG 问答编排 + 终态成对收尾）、
 * SourceRef（引用溯源 DTO）。
 *
 * <p>依赖规则：→ core（引擎与纯逻辑）→ infra（技术设施）→ dao（持久化）；
 * 阶段 3 的 AgentService 将落位于此并复用 infra.sse 的推流契约。
 */
package com.zhixing.service;
