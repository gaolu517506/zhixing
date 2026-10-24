# mcp-server-demo：知行 MCP 演示 server（stdio）

Node 实现的 MCP 演示 server（官方 `@modelcontextprotocol/sdk`，与前端同为 JS 栈——DL-17 ③），
供知行主应用经 stdio 子进程接入，验证 MCP 工具接入全链路。

## 工具

| 工具 | 类型 | 说明 |
|:--|:--|:--|
| `kb_stats` | 读 | 调知行后端汇总知识库统计（库数量、各库文档数、完成向量化数）。后端地址取环境变量 `ZHIXING_BASE`（默认 `http://localhost:9090`）；后端不可达或接口缺失时返回友好文本，不崩进程。已知：后端 `GET /api/kb` 列表接口尚未提供（FE/后端线已上报），届时本工具零改动自动恢复完整统计。 |
| `echo` | 读 | 回声演示（可选 `message` 参数，缺省 `ping`）——首次接入验证最小闭环。 |

## 启动说明

```bash
# 1) 安装依赖（本目录内）
npm install

# 2) 单独自检（stdio server 启动后挂起等待输入，Ctrl-C 退出）
npm start

# 3) 知行主应用接入：application.yaml（或环境变量）开启
#    zhixing.agent.mcp:
#      enabled: true          # 默认 false；开启后主应用启动时自动拉起本 server
#      command: node
#      args: mcp-server-demo/server.mjs
# 然后启动主应用，日志出现 "[mcp] 接入完成：注册 2 个远程工具" 即接入成功。
```

## 注意

- `node_modules` 不入库（`.gitignore` 已排除）：`enabled=true` 但未执行 `npm install` 时，
  主应用启动会告警降级（Agent 无 MCP 工具照常工作）——这是既定的优雅降级语义。
- 真实 server 联调（含 kb_stats 对真实后端的统计输出）由 lead 合流后组织。
