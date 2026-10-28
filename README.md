# 知行 Zhixing

[![CI](https://github.com/gl15121004754-hue/zhixing/actions/workflows/ci.yml/badge.svg)](https://github.com/gl15121004754-hue/zhixing/actions/workflows/ci.yml)

> 知 = 检索增强（RAG），行 = 工具执行（Agent）。取"知行合一"之意：让模型既有据可知，又能动而行。

**知行是一个生产级 Agentic RAG 平台**，面向企业私有知识库场景，提供从文档接入、混合检索到多轮流式问答与 Agent 执行的完整链路。

- 📄 **文档知识库**：PDF / Word 等格式一键接入，Tika 解析、智能分块、向量化入库，全程状态机可观测，同库同文件幂等去重
- 🔍 **混合检索**：pgvector 向量召回 + pg_trgm 关键词召回，RRF 融合、Rerank 扩展位，精确匹配与语义理解两路兼得
- 💬 **多轮流式问答**：SSE 增量输出，答案带引用溯源；问题重写消解指代（"它充电多少瓦？""那 K80 呢？"不迷路），近 N 轮记忆 + 滚动摘要；知识库无据时明确拒答，不编造
- 🤖 **Agent 执行**：自研 ReAct 引擎（非框架，~400 行核心循环），复合任务自主多轮调用工具；写操作经人工确认后执行（挂起恢复，确认可发生在 SSE 结束任意久之后）；接入 MCP 生态工具（stdio，本地工具重名优先，server 不可用自动降级）
- 🧠 **长期记忆**：回合级异步仲裁提炼用户偏好（新增 / 更新 / 撤回三态），跨会话自动注入——"记住我喜欢简短回答"说完即生效，下一次会话不用再说
- 🖥️ **Web 控制台**：聊天（含 Agent 思考过程折叠 / 工具调用时间线 / 确认弹窗）/ 知识库管理 / Trace 观测，开箱即用
- 🔌 **多模型接入**：DeepSeek / 智谱 GLM 开箱即用；一套抽象兼容所有 OpenAI 协议供应商，新供应商接入只写一个 20 行子类；首包超时检测 + 跨供应商自动降级 + 三态熔断
- 🛡️ **生产化内建**：统一响应协议与三段式错误码、链路追踪（Trace）落库可查、文档幂等、会话历史全量持久化

## 架构

```mermaid
flowchart LR
    subgraph web[Web 控制台]
        UI[聊天 / 知识库 / Trace 观测]
    end
    subgraph ingest[文档入库 Pipeline]
        A[PDF / Word / Markdown] --> B[Tika 解析] --> C[智能分块] --> D[Embedding 批量向量化] --> E[("PostgreSQL\npgvector + pg_trgm")]
    end
    subgraph qa[多轮检索问答 Pipeline]
        Q[用户提问] --> R[问题重写\n指代消解 + 关键词] --> F[混合检索\n向量 + 关键词双通道] --> G[RRF 融合] --> H[Prompt 组装\nsystem + 历史 + 资料] --> J[LLM 流式生成] --> K[SSE 推流\n答案 + 引用溯源]
    end
    subgraph agent[Agent Pipeline]
        S[任务输入] --> T[ReAct 循环\n思考 → 选工具 → 观察] --> U{需要工具?}
        U -- 检索/时间等内置工具 --> V[本地执行] --> T
        U -- MCP 远程工具 --> W[MCP Server\nstdio] --> T
        U -- 写操作 --> X[挂起等确认\n全量状态落库] -- 人工批准/拒绝 --> T
        T --> Y[结构化结果\nSSE 推流]
    end
    M[回合结束\n异步记忆仲裁] --> N[("agent_memory\nACTIVE/SUPERSEDED/RETRACTED")] -. 跨会话注入 .-> H
    UI -- REST / SSE --> Q
    UI -- 上传 / 状态轮询 --> A
    E --> F
    E -. 工具读写 .-> V
```

所有节点耗时通过内建 Trace 表落库，Web 控制台可视化查询，检索与生成全链路可观测。

## 快速开始

前置：JDK 17、Docker、Node.js 20+（MCP 演示工具需要；仅跑后端与前端可无 Node），以及 [DeepSeek](https://platform.deepseek.com/) 与 [智谱](https://open.bigmodel.cn/) API Key。

### 方式一：Docker Compose（推荐）

```bash
# 1. 配置模型 API Key（.env 已被 .gitignore 排除，不入库）
cp .env.example .env
vim .env          # 填入真实 key

# 2. 一键启动 PostgreSQL（pgvector/pg_trgm 自动扩展）+ 后端（自动建表）
docker compose up -d
# 首次构建需下载 Maven 依赖，请耐心等待；健康检查通过后服务在 9090 就绪

# 3. 启动前端
cd frontend && npm install && npm run dev

# 4. 打开 Web 控制台
# http://localhost:5173 → 知识库页上传文档 → 聊天页开始提问
```

### 方式二：本地开发

```bash
# 1. 启动 PostgreSQL（含 pgvector 扩展）
docker run -d --name zhixing-pg -e POSTGRES_PASSWORD=postgres -p 5432:5432 pgvector/pgvector:pg16
docker exec -i zhixing-pg psql -U postgres -c "CREATE DATABASE zhixing;"

# 2. 建表（V1→V2→V3 按版本顺序执行，自带 CREATE EXTENSION vector / pg_trgm）
for f in zhixing-bootstrap/src/main/resources/db/V*.sql; do
  docker exec -i zhixing-pg psql -U postgres -d zhixing < "$f"
done

# 3. 配置并启动后端
cp .env.example .env && vim .env && source .env
./mvnw spring-boot:run          # 默认连接 127.0.0.1:5432，可用 PG_URL/PG_USER/PG_PASSWORD 覆盖
# Agent 的 MCP 演示工具（kb_stats/echo）交付默认关闭；启用将 application.yaml 的
# zhixing.agent.mcp.enabled 置 true（需本机装有 Node.js 20+），启动日志见「注册 2 个远程工具」

# 4. 启动前端（同方式一第 3 步）

# 5. 验证
curl http://localhost:9090/api/health
# {"code":"0","message":"success","data":"zhixing up"}
```

数据库连接、模型 Key 均可通过环境变量覆盖（`PG_URL` / `PG_USER` / `PG_PASSWORD` / `DEEPSEEK_API_KEY` / `ZHIPU_API_KEY`；`SILICONFLOW_API_KEY` 选填，启用 embedding 备胎时需要）。

### 测试

```bash
./mvnw test    # 单测 231 例（224 例离线 + 7 例联网冒烟默认门控跳过）：SSE 解析、错误分类、分块、RRF、ReAct 状态机、MCP 生命周期（需 node）、会话记忆等

# 冒烟测试（真实调用模型 API，产生少量费用），需先 source .env
LLM_SMOKE=true ./mvnw test -Dtest='LlmSmokeTest,LlmZhipuPrimarySmokeTest'

# 检索质量评测 runner（Hit@5 / Recall@5 / MRR@10）
# 注：内置评估集与演示语料暂未随仓库分发（zhixing.eval.set-path 可指向自建评估集）
EVAL_RUN=true ./mvnw test -Dtest=EvalRunner
# 当前基线（内部评估集 29 题 × 10 篇语料，小语料集仅作回归参照）：
# Hit@5 = 1.000（easy/medium/hard 分层均 1.000）· Recall@5 = 0.966 · MRR@10 = 0.891
```

切换主力对话供应商**只改一行 yaml**：`zhixing.llm.chat.primary` 从 `deepseek/deepseek-chat` 改为 `zhipu/glm-4-flash`。

## 技术栈

| 维度 | 选型 | 说明 |
|:---|:---|:---|
| 语言/框架 | Java 17 + Spring Boot 3.5.x（Servlet 栈） | MVC + SseEmitter，流式输出零额外依赖 |
| ORM | MyBatis-Plus + 手写检索 SQL | 常规 CRUD 交给 MP；向量与融合检索手写 SQL，性能与行为可控 |
| 存储 | PostgreSQL 16 + pgvector + pg_trgm | **单库三用**：业务数据 + HNSW 向量索引 + GIN 全文索引，中间件 3 → 1 |
| 文档解析 | Apache Tika | 一个依赖覆盖 PDF / Word / PPT / HTML |
| 模型 | DeepSeek（对话主力）+ 智谱 GLM（备选 + embedding-3） | 均 OpenAI 兼容协议，一套抽象全覆盖；一主一备跨供应商降级 |
| Agent 工具生态 | MCP Java SDK（stdio） | 接入 MCP 生态任意工具 server；远程工具与本地内置工具统一 Tool 契约 |
| 前端 | React 18 + TypeScript + Vite + Tailwind CSS | 三页控制台；SSE 用 fetch 流式手解析（POST + SSE 不受 EventSource 限制） |

## 设计亮点

**模型客户端层**（`infra/llm/`）

- 模板方法基类 `AbstractOpenAIStyleChatClient`：请求构建、状态码分类、SSE 循环、容错全部钉死在基类，供应商差异收敛为两个钩子——接入新供应商 = 一个 `provider()` + 若干钩子覆写
- SSE 解析器 `OpenAiStyleSseParser` 设计为**纯函数、无状态、永不抛异常**：LLM 真实流无法复现，但解析逻辑可以离线穷举；前端 `sse.ts` 与之同构（帧状态跨 chunk 保留，粘包/半包有单测锁死）
- 容错三规则：单行解析失败降级跳过不断流；`finish_reason` / `[DONE]` 双重防御收尾（usage 块不丢、迟到 `[DONE]` 不毁答案）；HTTP 200 携带 error body 必须当失败
- `ErrorType` 枚举内建 `fallbackCapable` 标志：哪些错误值得切换备用供应商、哪些切了也没用，容错降级直接读属性
- embedding 备胎（`ResilientEmbeddingDecorator`）：`fallback: siliconflow/BAAI/bge-m3` 一行启用请求级切换——网络失败/5xx/429 自动切备重试一次，401/400 语义错误不白切；**维度守卫**拒绝一切维度不符的向量落库（主备必须同维度模型，启动时 WARN 提示）；默认空配置单通道运行，行为与无装饰器完全一致

**多轮对话**（`core/memory/`）

- 问题重写消解指代与省略（"它充电多少瓦" → "小米15 Ultra 充电多少瓦"），失败自动降级原问题，链路永不中断
- 会话记忆 = 近 8 轮完整对话（窗口偶数对齐）+ 滚动摘要（第 9 轮触发，≤200 字），token 预算可控
- 内置检索质量评测 runner（Hit@5 / Recall@5 / MRR@10），每次调参前后各跑一次，用数字而非感觉驱动优化；当前基线见「测试」一节

**Agent 引擎**（`core/agent/`）

- ReAct 循环状态机三条不变式：消息回填（assistant(toolCalls) 原样入列 + tool 结果按 toolCallId 配对紧随）、写工具挂起恢复（全量状态 JSONB 落库，人工确认可发生在 SSE 结束任意久之后）、连续失败防死循环（达阈值强制收尾并说明原因）
- Function Calling 流式解析：`delta.tool_calls` 增量碎片按 index 分桶聚合；SSE 事件流上每个终态语义与 done 严格成对，前端零特判
- 跨供应商容错：首包超时检测、按错误类型过滤候选（不可降级错误不白切）、三态熔断（3 次/30s/半开试探），对上层完全透明

**MCP 接入与长期记忆**（`infra/mcp/` · `core/memory/`）

- MCP 客户端（官方 Java SDK + stdio）：启动重试、指数退避重连、业务错误（isError）与传输错误语义分离不误触发重连；远程工具经 Adapter 适配为本地 Tool 契约，Agent 无感混用，重名本地优先；server 不可用优雅降级，Agent 照常工作
- 长期记忆三动作仲裁（ADD / SUPERSEDE / RETRACT）在回合结束后异步执行，失败静默绝不阻塞主链路；QA 与 Agent 双链路自动注入【已知用户偏好】——记忆跨会话生效，用户只说一次

**协议与可观测**

- HTTP 恒 200 + 业务码（`Result<T>`），错误码 A（客户端）/ B（服务端）/ C（远程模型）三段；前端一套解析逻辑通吃 JSON 与 SSE
- `trace_run` / `trace_node` 边写边埋点，Web 控制台可视化每个检索与生成节点的耗时

## 项目结构

Maven 多模块（依赖方向单向：bootstrap → core → llm → framework），每个包的职责与依赖规则见对应 `package-info.java`——包结构即架构文档。

```
zhixing
├── zhixing-framework   协议（common/Result+错误码）、配置装配（config/）、SSE 推流设施（infra/sse）
├── zhixing-llm         自研 OpenAI 兼容模型客户端（模板方法基类 + 纯函数 SSE 解析器 + 容错降级）
├── zhixing-core        核心域引擎与编排
│   ├── core/           ingest（入库管线）· retrieval（双通道+RRF）· memory（重写/记忆/摘要/长期记忆）
│   │                   eval（评估纯函数）· agent（ReAct 引擎/工具契约/确认管理）
│   ├── infra/          vector（pgvector）· keyword（pg_trgm）· storage · trace · mcp（MCP 接入）
│   ├── dao/            MyBatis-Plus entity/mapper（唯一手写 SQL 的层）
│   └── service/        用例编排：KbService · QaService · AgentService · agenttools（Agent 工具）
├── zhixing-bootstrap   Application + controller + application.yaml + prompts + db 脚本（可执行 jar）
└── frontend/           React 18 + TS + Vite + Tailwind 三页控制台
```

每个包的职责与依赖规则见对应 `package-info.java`——包结构即架构文档。

## Roadmap

| 版本 | 交付 |
|:--|:---|
| v0.1 ✅ | RAG 全链路 + 多轮对话 + Web 控制台三页 + Docker Compose 一键部署 |
| v0.2 ✅ | 自研 ReAct Agent（复合任务）、写操作人工确认与挂起恢复、跨供应商容错降级与熔断、Agent 事件前端（thinking / 工具时间线 / 确认弹窗） |
| v1.0 ✅ | MCP 工具接入、简化版长期记忆（跨会话偏好注入）、单体 → 四模块拆分 |
| **v1.1 ✅（当前）** | CI 门禁与开源卫生、embedding 备胎容错、评测数字公开、前端三页真实化、全量代码评审修复（三批次：confirm CAS / 会话回放 / SSE 收尾与错误细分 / trace seq / 404 语义 / MCP 交付默认） |

## License

[MIT](LICENSE)
