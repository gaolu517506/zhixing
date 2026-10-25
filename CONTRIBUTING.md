# 贡献指南（CONTRIBUTING）

感谢关注知行（Zhixing）。这是一份务实的指南，目标是让每一次贡献都能被快速、愉快地 review——不搞形式主义流程。

## 环境要求

- JDK 17（Temurin 推荐）
- Node.js 20+
- Docker（PostgreSQL 16 + pgvector 由 `docker compose` 提供）
- PostgreSQL 16（pgvector / pg_trgm 扩展；compose 首次启动自动建扩展建表）

具体安装与启动步骤见 [README](README.md#快速开始)，此处不重复。

## 本地验证最低标准

提交前请在本地跑通（CI 会在 PR 上再跑一遍同样的东西）：

```bash
./mvnw test                        # 后端全量测试
cd frontend && npm run build && npm test   # 前端类型检查 + 构建 + 单测
```

说明：后端有少量联网冒烟测试（LLM API 调用，花钱），由环境变量（`LLM_SMOKE` / `EVAL_RUN` / `INGEST_SMOKE`）门控，默认跳过属预期——CI 与本地零密钥环境都是绿的。

## 分支与提交

- 分支名：`feat/` `fix/` `chore/` `docs/` 前缀 + 简短英文或拼音标识。
- 提交信息沿用仓库现有风格：`type(scope): 中文/英文简述`，例如（取自 git log）：
  - `fix(docker): 镜像 jar 路径错位修复与 compose 三份建表脚本挂载`
  - `docs: README 项目结构对齐四模块架构`
  - `chore(mcp): 补充 demo server 依赖锁文件`
- 小步提交：一个提交说清一件事，方便回滚与 review。

## Pull Request

1. 描述里写清**动机**与**验证方式**（贴关键测试输出即可）。
2. 保持小步：一个 PR 解决一个问题，几百行以内最容易被认真 review。
3. CI 绿后等待 review；review 意见对事不对人，有分歧摆事实（测试输出、文档依据）。
4. 请使用仓库自带的 PR 模板与 issue 模板（表单字段即 review 关注点）。

## 报告问题

- 缺陷请走 [Bug report 模板](.github/ISSUE_TEMPLATE/bug_report.yml)：复现步骤写全，**日志先脱敏再贴**。
- 功能建议走 [Feature request 模板](.github/ISSUE_TEMPLATE/feature_request.yml)：先说场景痛点，再说方案。

## 行为准则

保持尊重，对事不对人。我们讨论的是代码、设计与事实，不是提出问题的人；不同水平的贡献者都受欢迎，措辞上的体面是这个仓库的底线。
