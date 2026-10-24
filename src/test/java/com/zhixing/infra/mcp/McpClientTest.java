package com.zhixing.infra.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * McpClient 离线生命周期测试（任务书 §4）：对测试资源里的 Node 假 server
 * （固定响应脚本，零依赖手写 stdio JSON-RPC）做启动/发现/调用/子进程崩溃重连。
 * Node 是本机已有运行时（任务书豁免，不算外部依赖）；无 node 时整类跳过。
 */
@EnabledIf("com.zhixing.infra.mcp.McpClientTest#nodeAvailable")
class McpClientTest {

    private static final Path FAKE_SERVER =
            Path.of("src/test/resources/mcp-test/fake-server.mjs").toAbsolutePath();

    private McpClient client;

    @BeforeAll
    static void checkEnvironment() {
        assumeTrue(nodeAvailable(), "本机无 node 运行时");
        assumeTrue(Files.exists(FAKE_SERVER), "假 server 脚本缺失");
    }

    static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "-v").start();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        // 退避基数 100ms（重连测试加速）；请求超时 2s（die 无响应路径尽快解锁）
        client = new McpClient("node", List.of(FAKE_SERVER.toString()), 100, Duration.ofSeconds(2));
        client.start();
    }

    @AfterEach
    void tearDown() {
        client.close(); // 销毁子进程，防僵尸
    }

    @Test
    void 启动发现_固定工具的名称描述与schema() {
        var tools = client.listTools();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("echo_tool");
        assertThat(tools.get(0).description()).contains("假 server 固定回声工具");
        assertThat(tools.get(0).inputSchema()).containsEntry("type", "object");
    }

    @Test
    void 调用回声工具_拼接text结果() {
        var result = client.callTool("echo_tool", Map.of("message", "你好 MCP"));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).isEqualTo("fake-echo: 你好 MCP");
    }

    @Test
    void 远端isError结果_原样透传不触发重连() {
        var result = client.callTool("echo_tool", Map.of("message", "__error__"));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).isEqualTo("fake-error: 业务失败回填");
    }

    @Test
    void 子进程崩溃后自动重连_后续调用恢复() {
        // die：假 server 不回包直接 exit(1)——客户端超时 → 重连（退避）→ 重试同一操作直至耗尽
        assertThatThrownBy(() -> client.callTool("die", Map.of()))
                .isInstanceOf(McpClient.McpUnavailableException.class);

        // 旧子进程已死：下一次调用经 ensureConnected 重连新进程后恢复
        var result = client.callTool("echo_tool", Map.of("message", "reborn"));
        assertThat(result.text()).isEqualTo("fake-echo: reborn");
    }

    @Test
    void 关闭后操作抛IllegalStateException() {
        client.close();

        assertThatThrownBy(() -> client.listTools())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
    }

    @Test
    void 启动失败_重连耗尽后抛McpUnavailable() {
        McpClient bad = new McpClient("no-such-command-xyz", List.of(), 50, Duration.ofSeconds(1));

        assertThatThrownBy(bad::start)
                .isInstanceOf(McpClient.McpUnavailableException.class)
                .hasMessageContaining("MCP 启动失败");
        assertThatThrownBy(bad::start).isInstanceOf(McpClient.McpUnavailableException.class); // 幂等失败
    }
}
