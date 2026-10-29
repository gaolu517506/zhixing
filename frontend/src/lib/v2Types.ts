/**
 * v2.0 M2 契约类型（eval runs / admin / 种子）。
 * 形态依据 **02-技术方案 §B6/B7 冻结契约（联调对齐线勘误，与线 D 实现同源）**；
 * demo 态由 v2Mock 同形提供。前端 id 全程 string（后端 Long→String 全局序列化）。
 */

export interface AdminUser {
  id: string;
  username: string;
  /** ACTIVE / DISABLED */
  status: string;
  /** ADMIN / USER */
  systemRole: string;
  /** 从 LOGIN 审计推导（02 §B7 冻结契约）；从未登录（如新建邀请）为 null。 */
  lastLoginAt: string | null;
  createdAt?: string;
}

export interface KbMember {
  userId: string;
  username: string;
  /** ADMIN / MEMBER 两档（02 §B7 库角色两档下拉） */
  role: string;
}

export interface AdminKb {
  id: string;
  name: string;
  /** PRIVATE / ALL */
  visibility: string;
  createdAt?: string;
}

/** 供应商熔断现态（02 §B7：读熔断器现态，默认不主动 ping）。 */
export interface ProviderHealth {
  name: string;
  /** CLOSED / OPEN / HALF_OPEN */
  circuitState: string;
}

/** 健康聚合（02 §B7 冻结契约：db 对象、providers 数组、counts 含 agentRuns）。 */
export interface HealthSnapshot {
  backend: string;
  db: { up: boolean; pingMs: number };
  storage: { type: string; writable: boolean; usageBytes: string };
  providers: ProviderHealth[];
  /** 计数后端为 Long→string 序列化，渲染直显。 */
  counts: { docs: number | string; kbs: number | string; users: number | string; agentRuns: number | string };
}

export interface AuditItem {
  id: string;
  actorId: string | null;
  actorName: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  /** OK / FAIL */
  result: string;
  detail: string | null;
  createdAt: string;
}

export interface AuditPage {
  items: AuditItem[];
  total: number;
  page: number;
  size: number;
}

export interface AuditFilter {
  from?: string;
  to?: string;
  actorId?: string;
  action?: string;
  page?: number;
  size?: number;
}

// ===== eval runs（§B6 冻结契约）=====

/** 难度分层（report.layers 内按 easy/medium/hard 键取；该层无题时键可缺席）。 */
export interface EvalLayer {
  cases: number;
  hitAt5: number;
  recallAt5?: number;
  mrrAt10?: number;
}

export interface EvalDelta {
  vsLastRunId: string;
  hitAt5: number;
  recallAt5: number;
  mrrAt10: number;
}

/** EvalRunView.report（JSONB 字符串解析后）：语料规模诚实标注以 corpus 快照呈现。 */
export interface EvalReport {
  hitAt5: number;
  recallAt5: number;
  mrrAt10: number;
  /** 题数（注意：非逐题数组——逐题明细后端不提供，运行步降级为进度展示）。 */
  cases: number;
  layers: { easy?: EvalLayer; medium?: EvalLayer; hard?: EvalLayer };
  corpus: { docs: number; chunks: number };
  delta?: EvalDelta | null;
}

/** 逐题结果（运行步表格；真实契约不提供，仅 demo mock 同形演示）。 */
export interface EvalCaseView {
  question: string;
  hit: boolean;
  expectedDocIds: string[];
  topDocIds: string[];
}

export interface EvalRun {
  id: string;
  kbId: string;
  /** RUNNING / COMPLETED / FAILED */
  status: string;
  startedAt: string;
  finishedAt?: string | null;
  errorMsg?: string | null;
  report?: EvalReport | null;
  cases?: EvalCaseView[] | null;
  /** mock 内部记录发起来源（不出现在真实契约） */
  _source?: { setPath?: string; setJson?: string };
}
