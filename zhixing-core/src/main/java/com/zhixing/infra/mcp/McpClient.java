package com.zhixing.infra.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MCP stdio 客户端（详设 14-v1.0 §4.2）：管理子进程与会话生命周期——
 * 启动/initialize、tools/list 发现、tools/call 转发、断线重连、退出清理。
 *
 * <p>传输与子进程由 SDK 的 {@link StdioClientTransport} 托管（close 即销毁子进程，防僵尸）；
 * 本类在其上补齐运维语义：操作失败 → 关闭旧会话 → 指数退避重连（≤3 次）→ 重试操作——
 * 重连与重试合并为一轮，3 次重连全部失败即抛出，由 McpBootstrap 降级告警。
 *
 * <p>线程模型：所有操作 synchronized——stdio JSON-RPC 客户端不支持并发请求，
 * 多工具并发执行时在此排队（单用户规模无性能问题）。
 */
@Slf4j
public class McpClient implements AutoCloseable {

    /** 重连上限（不含首次）：超过即判定 MCP 不可用。 */
    static final int MAX_RECONNECT_ATTEMPTS = 3;

    private final String command;

    private final List<String> args;

    /** 重连退避基数（毫秒），第 n 次重连前等待 base * 2^(n-1)；测试注入小值加速。 */
    private final long retryBaseBackoffMs;

    /** 请求超时：发现/调用都不能无限等（子进程假死时靠它解锁）。 */
    private final Duration requestTimeout;

    private McpSyncClient client;

    private boolean closed;

    public McpClient(String command, List<String> args, long retryBaseBackoffMs) {
        this(command, args, retryBaseBackoffMs, Duration.ofSeconds(15));
    }

    public McpClient(String command, List<String> args, long retryBaseBackoffMs, Duration requestTimeout) {
        this.command = command;
        this.args = List.copyOf(args);
        this.retryBaseBackoffMs = retryBaseBackoffMs;
        this.requestTimeout = requestTimeout;
    }

    /** 启动子进程并完成 initialize 握手；失败按重连退避重试（共 1+MAX 次），耗尽抛 McpUnavailable。 */
    public synchronized void start() {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                long backoff = retryBaseBackoffMs * (1L << (attempt - 1));
                log.warn("[mcp] start 第 {}/{} 次重试，退避 {}ms", attempt, MAX_RECONNECT_ATTEMPTS, backoff);
                sleep(backoff);
                closeClientQuietly();
                client = null;
            }
            try {
                connect();
                return;
            } catch (RuntimeException e) {
                last = e;
                closeClientQuietly();
                client = null;
                log.warn("[mcp] start 失败（attempt={}）：{}", attempt, e.getMessage());
            }
        }
        throw new McpUnavailableException("MCP 启动失败（重连 " + MAX_RECONNECT_ATTEMPTS + " 次仍失败）", last);
    }

    /** 发现远程工具（断线自动重连后重试）。 */
    public synchronized List<McpSchema.Tool> listTools() {
        return withReconnect("tools/list", c -> c.listTools().tools());
    }

    /**
     * 调用远程工具：content 数组取 text 拼接为结果文本；isError=true 是远端工具的业务
     * 失败结果——以 {@link McpToolCallResult#isError()} 标记原样返回（适配层转
     * ToolResult.fail 回填模型自纠），不触发重连。
     */
    public synchronized McpToolCallResult callTool(String name, Map<String, Object> arguments) {
        return withReconnect("tools/call", c -> {
            McpSchema.CallToolResult result = c.callTool(new McpSchema.CallToolRequest(name, arguments));
            String text = result.content().stream()
                    .filter(content -> content instanceof McpSchema.TextContent)
                    .map(content -> ((McpSchema.TextContent) content).text())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
            // isError=true 是远端工具的业务失败结果（有效应答）：原样返回文本交由适配层
            // 转 ToolResult.fail 回填模型自纠——不抛异常、不触发重连
            return new McpToolCallResult(text, Boolean.TRUE.equals(result.isError()));
        });
    }

    /** 关闭会话并销毁子进程（幂等；关闭后所有操作抛 IllegalStateException）。 */
    @Override
    public synchronized void close() {
        closed = true;
        closeClientQuietly();
    }

    private <T> T withReconnect(String operation, Function<McpSyncClient, T> body) {
        if (closed) {
            throw new IllegalStateException("McpClient 已关闭");
        }
        RuntimeException last = null;
        // 第 0 次为当前连接上的原样尝试；其后至多 MAX 次「重连 + 重试」
        for (int attempt = 0; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                long backoff = retryBaseBackoffMs * (1L << (attempt - 1));
                log.warn("[mcp] {} 第 {}/{} 次重连，退避 {}ms", operation, attempt, MAX_RECONNECT_ATTEMPTS, backoff);
                sleep(backoff);
                closeClientQuietly();
                client = null;
            }
            try {
                ensureConnected();
                T result = body.apply(client);
                if (attempt > 0) {
                    log.info("[mcp] {} 重连后恢复成功", operation);
                }
                return result;
            } catch (RuntimeException e) {
                last = e;
                log.warn("[mcp] {} 失败（attempt={}）：{}", operation, attempt, e.getMessage());
            }
        }
        throw new McpUnavailableException(operation + " 重连 " + MAX_RECONNECT_ATTEMPTS + " 次仍失败", last);
    }

    private synchronized void ensureConnected() {
        if (closed) {
            throw new IllegalStateException("McpClient 已关闭");
        }
        if (client != null) {
            return;
        }
        connect();
    }

    private void connect() {
        ServerParameters params = ServerParameters.builder(command).args(args).build();
        // 本类与 SDK 工厂类同名：sync(...) 必须全限定，否则解析到自身
        McpSyncClient newClient = io.modelcontextprotocol.client.McpClient
                .sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(requestTimeout)
                .build();
        newClient.initialize();
        this.client = newClient;
        log.info("[mcp] 子进程已启动并完成 initialize：{} {}", command, String.join(" ", args));
    }

    private void closeClientQuietly() {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("[mcp] 旧会话关闭异常（忽略）：{}", e.getMessage());
        }
        client = null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重连等待被中断", e);
        }
    }

    /** tools/call 的结果：text 为 content 数组 text 拼接；isError=true 表示远端业务失败。 */
    public record McpToolCallResult(String text, boolean isError) {
    }

    /** 重连上限耗尽 / 子进程无法启动：由 McpBootstrap 降级告警（Agent 无 MCP 照常工作）。 */
    public static class McpUnavailableException extends RuntimeException {
        public McpUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
