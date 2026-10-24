#!/usr/bin/env node
/**
 * 知行 MCP 演示 server（stdio 传输，台账 T4-3 / 详设 14-v1.0 §4.1，DL-17 ③）。
 *
 * 工具：
 * - kb_stats：调知行后端汇总知识库统计（库数量、各库文档数与完成向量化数）。
 *   后端地址取环境变量 ZHIXING_BASE（默认 http://localhost:9090）；
 *   后端不可达或接口缺失时返回友好错误文本（不崩进程）。
 * - echo：回声演示（无参/单参调用教学样本），用于首次接入验证最小闭环。
 *
 * 启动：见同目录 README.md（npm install 后由知行主应用按 zhixing.agent.mcp 拉起）。
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const ZHIXING_BASE = (process.env.ZHIXING_BASE || "http://localhost:9090").replace(/\/$/, "");

const server = new McpServer({ name: "zhixing-demo", version: "0.1.0" });

server.tool(
  "echo",
  "回声工具：原样返回传入的 message 文本。无任何副作用，用于演示 MCP 工具调用的最小闭环与首次接入自检；除自检外通常没有调用它的必要。",
  { message: z.string().optional().describe("要回声的文本；缺省返回 ping") },
  async ({ message }) => ({
    content: [{ type: "text", text: "echo: " + (message || "ping") }],
  }),
);

server.tool(
  "kb_stats",
  "汇总知行知识库统计：库数量、各库文档总数与完成向量化的文档数。当用户询问知识库里有什么、文档规模，或回答前需要确认知识库内容覆盖时调用；后端不可达时返回友好提示而非失败。",
  {},
  async () => {
    try {
      const res = await fetch(`${ZHIXING_BASE}/api/kb`);
      const body = await res.json().catch(() => ({}));
      if (!res.ok || body.code !== "0") {
        return {
          content: [{
            type: "text",
            text: `知行后端的知识库列表接口暂不可用（${body.code || "HTTP " + res.status}）。`
              + `可先调用健康检查确认服务状态；该接口补齐后本工具自动恢复完整统计。`,
          }],
        };
      }
      const knowledgeBases = body.data || [];
      const lines = [];
      for (const kb of knowledgeBases) {
        try {
          const docsRes = await fetch(`${ZHIXING_BASE}/api/kb/${kb.id}/documents`);
          const docsBody = await docsRes.json();
          const docs = docsBody.code === "0" ? docsBody.data || [] : [];
          const completed = docs.filter((d) => d.status === "COMPLETED").length;
          lines.push(`· ${kb.name}（id=${kb.id}）：文档 ${docs.length} 篇，完成向量化 ${completed} 篇`);
        } catch (err) {
          lines.push(`· ${kb.name}（id=${kb.id}）：文档清单获取失败（${err.message}）`);
        }
      }
      return {
        content: [{
          type: "text",
          text: `知识库共 ${knowledgeBases.length} 个：\n` + lines.join("\n"),
        }],
      };
    } catch (err) {
      return {
        content: [{
          type: "text",
          text: `知行后端 ${ZHIXING_BASE} 不可达：${err.message}。请确认后端服务已启动后重试。`,
        }],
      };
    }
  },
);

await server.connect(new StdioServerTransport());
// stdio：保持 stdin 打开即常驻；父进程关闭 stdin/退出时 SDK 自行收尾
