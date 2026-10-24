/**
 * 核心域 · 检索质量评估（决策 D11）：纯函数指标 + 评估集解析，与 runner 执行解耦。
 *
 * <p>核心类：EvalMetrics（Hit@K / Recall@K / MRR@K）、EvalSetSupport（jsonl 解析与分层聚合）。
 * EvalRunner（执行骨架，EVAL_RUN 门控）在 src/test 下——评测是调优动作不进生产类路径。
 *
 * <p>依赖规则：纯函数无 Spring 依赖，离线可测；基线数字入 decisions.md 评测基线记录。
 */
package com.zhixing.core.eval;
