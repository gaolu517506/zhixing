#!/usr/bin/env node
/**
 * MCP 测试假 server（零依赖，手写 stdio JSON-RPC）——McpClientTest 专用固定响应脚本。
 *
 * 行为：
 * - initialize → 固定 serverInfo/capabilities；
 * - tools/list → 恒定返回单个 echo_tool（固定 description 与 inputSchema）；
 * - tools/call echo_tool → "fake-echo: <message||ping>"；
 * - tools/call echo_tool 且 message="__error__" → isError=true 的结果（透传业务失败）；
 * - tools/call die → 立即 process.exit(1)（模拟子进程崩溃，用于断线重连测试）；
 * - 未知方法 → JSON-RPC error。
 * 传输：MCP stdio 规范 = 按行分隔的 JSON-RPC（UTF-8）。
 */
let buffer = "";

process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  let index;
  while ((index = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (line) {
      handle(line);
    }
  }
});

process.stdin.on("end", () => {
  process.exit(0); // 父进程关闭 stdin：正常收尾，不留僵尸
});

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + "\n");
}

function handle(line) {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return; // 坏行忽略
  }
  if (msg.id === undefined || msg.id === null) {
    return; // notification（如 notifications/initialized）：无需应答
  }
  switch (msg.method) {
    case "initialize":
      send({
        jsonrpc: "2.0",
        id: msg.id,
        result: {
          protocolVersion: "2024-11-05",
          capabilities: { tools: {} },
          serverInfo: { name: "fake-mcp", version: "0.0.1" },
        },
      });
      return;
    case "tools/list":
      send({
        jsonrpc: "2.0",
        id: msg.id,
        result: {
          tools: [
            {
              name: "echo_tool",
              description: "假 server 固定回声工具（离线测试用）",
              inputSchema: {
                type: "object",
                properties: { message: { type: "string" } },
                required: [],
              },
            },
          ],
        },
      });
      return;
    case "tools/call": {
      const name = msg.params && msg.params.name;
      if (name === "die") {
        // 模拟子进程崩溃：不回包直接退出（客户端超时后应走重连）
        process.exit(1);
      }
      const message = (msg.params && msg.params.arguments && msg.params.arguments.message) || "ping";
      if (message === "__error__") {
        send({
          jsonrpc: "2.0",
          id: msg.id,
          result: {
            content: [{ type: "text", text: "fake-error: 业务失败回填" }],
            isError: true,
          },
        });
        return;
      }
      send({
        jsonrpc: "2.0",
        id: msg.id,
        result: { content: [{ type: "text", text: "fake-echo: " + message }] },
      });
      return;
    }
    default:
      send({
        jsonrpc: "2.0",
        id: msg.id,
        error: { code: -32601, message: "method not found: " + msg.method },
      });
  }
}
