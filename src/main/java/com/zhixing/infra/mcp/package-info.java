package com.zhixing.infra.mcp;

/**
 * MCP 工具接入（台账 T4-3，详设 14-v1.0 §4，DL-17 ③）。
 *
 * <p>McpClient 管理 stdio 子进程与 MCP 会话生命周期（initialize / tools/list / tools/call /
 * 断线重连）；McpToolAdapter 把远程工具适配为 {@link com.zhixing.core.agent.Tool}；
 * McpBootstrap 在 ApplicationReadyEvent 条件装配地完成发现与注册（失败降级不终态）。
 *
 * <p>红线：本包只消费 core/agent 的 Tool/ToolResult 契约，不反向依赖引擎与 service。
 */
