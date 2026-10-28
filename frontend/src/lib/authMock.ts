/**
 * /api/auth/* 的 mock 实现（任务书 29-§1 行为规格；线 A 未合入前的开发后端）。
 *
 * 状态存 localStorage（zhixing.auth.mock.* 键），行为对齐契约：
 * setup 仅空表可用并建 ADMIN → setup-status 翻 false；登录失败统一「账号或密码错误」
 * （防枚举），连续失败 5 次锁 5 分钟；me 校验 token，无效按 401 语义抛 UnauthorizedError。
 * 线 A 合流联调后本模块仅测试使用（VITE_AUTH_MOCK=false 切真实端点）。
 */
import { UnauthorizedError } from "./auth";
import { ApiError } from "./json";
import type { AuthUser, LoginResponse, SetupStatus } from "./types";

const USERS_KEY = "zhixing.auth.mock.users";
const SESSIONS_KEY = "zhixing.auth.mock.sessions";
const LOCK_KEY = "zhixing.auth.mock.lock";

const LOCK_THRESHOLD = 5;
const LOCK_MS = 5 * 60_000;
const MOCK_LATENCY_MS = 120;

interface StoredUser extends AuthUser {
  password: string;
}

interface LockState {
  count: number;
  until: number;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function fail(code: string, message: string): never {
  throw new ApiError(code, message);
}

function readJson<T>(key: string, fallback: T): T {
  const raw = window.localStorage.getItem(key);
  return raw ? (JSON.parse(raw) as T) : fallback;
}

function writeJson(key: string, value: unknown): void {
  window.localStorage.setItem(key, JSON.stringify(value));
}

function readUsers(): StoredUser[] {
  return readJson<StoredUser[]>(USERS_KEY, []);
}

function readSessions(): Record<string, string> {
  return readJson<Record<string, string>>(SESSIONS_KEY, {});
}

function readLock(username: string): LockState {
  return readJson<Record<string, LockState>>(LOCK_KEY, {})[username] ?? { count: 0, until: 0 };
}

function writeLock(username: string, state: LockState): void {
  const all = readJson<Record<string, LockState>>(LOCK_KEY, {});
  all[username] = state;
  writeJson(LOCK_KEY, all);
}

/** mock 用户 id：string 纪律（Id 一律 string），不参与真实雪花体系。 */
function mockId(): string {
  return `mock-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e9).toString(36)}`;
}

function mintToken(userId: string): string {
  const token = `mock-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
  writeJson(SESSIONS_KEY, { ...readSessions(), [token]: userId });
  return token;
}

function toPublic(user: StoredUser): AuthUser {
  const { password: _password, ...publicPart } = user;
  return publicPart;
}

export const authMock = {
  async setupStatus(): Promise<SetupStatus> {
    await sleep(MOCK_LATENCY_MS);
    return { needSetup: readUsers().length === 0 };
  },

  /** 首启第一步：仅空表可用，创建 ADMIN 并直接签发会话（向导免二次登录，联调对齐点）。 */
  async setup(input: { username: string; password: string }): Promise<LoginResponse> {
    await sleep(MOCK_LATENCY_MS);
    if (readUsers().length > 0) {
      fail("A0000", "系统已完成初始化，请直接登录");
    }
    const username = input.username.trim();
    if (!username) {
      fail("A0000", "请输入管理员账号");
    }
    if (input.password.length < 6) {
      fail("A0000", "密码至少 6 位");
    }
    const user: StoredUser = {
      id: mockId(),
      username,
      displayName: username,
      systemRole: "ADMIN",
      password: input.password,
    };
    writeJson(USERS_KEY, [user]);
    return { token: mintToken(user.id), user: toPublic(user) };
  },

  async login(input: { username: string; password: string }): Promise<LoginResponse> {
    await sleep(MOCK_LATENCY_MS);
    const username = input.username.trim();
    const lock = readLock(username);
    if (lock.until > Date.now()) {
      fail("A0000", "尝试过多，请 5 分钟后再试");
    }
    const user = readUsers().find((u) => u.username === username);
    if (!user || user.password !== input.password) {
      const count = lock.count + 1;
      writeLock(username, count >= LOCK_THRESHOLD
          ? { count, until: Date.now() + LOCK_MS }
          : { count, until: 0 });
      // 防枚举：账号不存在与密码错误统一文案
      fail("A0000", "账号或密码错误");
    }
    writeLock(username, { count: 0, until: 0 });
    return { token: mintToken(user.id), user: toPublic(user) };
  },

  async me(): Promise<AuthUser> {
    await sleep(MOCK_LATENCY_MS);
    const token = window.localStorage.getItem("zhixing.auth.token");
    const userId = token ? readSessions()[token] : undefined;
    const user = userId ? readUsers().find((u) => u.id === userId) : undefined;
    if (!user) {
      throw new UnauthorizedError();
    }
    return toPublic(user);
  },

  async logout(): Promise<void> {
    const token = window.localStorage.getItem("zhixing.auth.token");
    if (token) {
      const sessions = readSessions();
      delete sessions[token];
      writeJson(SESSIONS_KEY, sessions);
    }
  },

  /** 加载演示库（F8 第二步可选动作）：mock 模拟播种耗时；真实端点契约待线 A/D 对齐。 */
  async loadDemoKb(): Promise<void> {
    await sleep(400);
  },
};
