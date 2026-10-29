/**
 * v2.0 M2 契约类型（eval runs / admin / 种子进度）。
 * 形态依据 02-技术方案 §B6/B7 与 31-任务书 §1；线 D 未合入的端点由 demo mock 同形提供。
 */

export interface AdminUser {
  id: string;
  username: string;
  /** ACTIVE / DISABLED */
  status: string;
  /** ADMIN / USER */
  systemRole: string;
  lastLoginAt: string | null;
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
}

/** 供应商熔断现态（02 §B7：读熔断器现态，默认不主动 ping） */
export interface ProviderHealth {
  name: string;
  /** CLOSED / OPEN / HALF_OPEN */
  circuitState: string;
}

export interface HealthSnapshot {
  backend: string;
  db: string;
  storage: { type: string; writable: boolean; usageBytes: string };
  providers: { primary: ProviderHealth; fallback: ProviderHealth };
  counts: { docs: number; kbs: number; users: number };
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

// ===== eval runs（§B6）=====

export interface EvalLayer {
  hitAt5: number;
  cases: number;
}

export interface EvalDelta {
  vsLastRunId: string;
  hitAt5: number;
  recallAt5: number;
  mrrAt10: number;
}

export interface EvalReport {
  hitAt5: number;
  recallAt5: number;
  mrrAt10: number;
  /** 语料规模诚实标注（PRD §5.2⑤：固定展示，禁止包装成 benchmark） */
  corpusSize: number;
  corpusNote: string;
  layers: { easy: EvalLayer; medium: EvalLayer; hard: EvalLayer };
  delta?: EvalDelta;
}

/** 逐题结果（运行步流式表格；后端可选提供，缺失时只展示聚合指标） */
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
