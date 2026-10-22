package com.zhixing.infra.llm;

import com.zhixing.infra.llm.dto.ChatRequest;
import com.zhixing.infra.llm.dto.ChatResponse;
import com.zhixing.infra.llm.dto.ChatUsage;
import com.zhixing.infra.llm.dto.ToolCall;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 容错装饰器（详设 13-阶段3 §11，DL-15 ④/DL-8 兑现）：包装候选链 primary→fallback，对上层完全透明。
 *
 * <p><b>call()</b>：ModelClientException 且 {@code ErrorType.isFallbackCapable()} → 切下一候选
 * （每候选恰 1 次尝试）；非可降级错误（UNAUTHORIZED 等）直接上抛不切换、不计熔断。
 *
 * <p><b>stream()</b>（本类最隐蔽处）：每个候选在独立线程上执行，装饰线程等待三结局之一——
 * ①正常完成；②首包前失败（可降级）→ 切下一候选重启流；③<b>首包超时</b>
 * （first-token-timeout-ms 内无任何回调）→ 计时器隔离旧流 + interrupt 尽力打断 → 切下一候选。
 * <b>切换后旧流回调不得串流</b>：包装回调带隔离开关，一旦候选被隔离，其后续所有回调
 * （含迟到的内容增量与 onError/onComplete）一律吞掉——被中断的旧流可能在 socket 读超时后才
 * 挣扎出回调，绝不允许它污染新候选的流。已出首包后的流中失败不切换（切换会导致上层收到
 * 重复内容），直接按错误收尾。
 *
 * <p><b>熔断三态</b>（每候选独立，synchronized 状态机）：连续可降级失败 ≥ failure-threshold →
 * open open-seconds → 半开放行 half-open-attempts 次试探（成功闭合 / 失败重开）；
 * 熔断中的候选直接跳过，全部熔断时以 SERVER_ERROR 收尾。状态切换打 warn 日志。
 */
@Slf4j
public class ResilientChatDecorator implements ChatClient {

    /** 全局共享的首包超时计时器：任务极轻，单 daemon 线程足够，随类共存无需关闭。 */
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER;

    static {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "llm-failover-timer");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        TIMEOUT_SCHEDULER = executor;
    }

    private final List<ChatClient> candidates;

    private final CircuitBreaker[] breakers;

    private final long firstTokenTimeoutMs;

    public ResilientChatDecorator(List<ChatClient> candidates, long firstTokenTimeoutMs,
                                  int failureThreshold, int openSeconds, int halfOpenAttempts) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("候选链不能为空");
        }
        this.candidates = List.copyOf(candidates);
        this.firstTokenTimeoutMs = firstTokenTimeoutMs;
        this.breakers = new CircuitBreaker[this.candidates.size()];
        for (int i = 0; i < this.candidates.size(); i++) {
            // 注意单位换算：配置为秒，熔断器内部用毫秒（漏乘 1000 会让熔断形同虚设——单测抓出）
            this.breakers[i] = new CircuitBreaker(candidateName(i), failureThreshold,
                    TimeUnit.SECONDS.toMillis(openSeconds), halfOpenAttempts);
        }
    }

    @Override
    public String provider() {
        StringBuilder names = new StringBuilder("resilient[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                names.append('|');
            }
            names.append(candidates.get(i).provider());
        }
        return names.append(']').toString();
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        ModelClientException lastFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            CircuitBreaker breaker = breakers[i];
            if (!breaker.tryAcquire()) {
                log.warn("[容错] 候选 {} 熔断中（{}），call 跳过", candidateName(i), breaker.describe());
                continue;
            }
            try {
                ChatResponse response = candidates.get(i).call(request);
                breaker.recordSuccess();
                return response;
            } catch (ModelClientException e) {
                if (!e.getType().isFallbackCapable()) {
                    throw e; // 非可降级（UNAUTHORIZED 等）：切了也没用，直接上抛、不计熔断
                }
                breaker.recordFailure();
                lastFailure = e;
                log.warn("[容错] 候选 {} call 可降级失败({})，切换下一候选", candidateName(i), e.getType());
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new ModelClientException(ErrorType.SERVER_ERROR, "所有模型候选均处于熔断中");
    }

    @Override
    public void stream(ChatRequest request, StreamCallback callback) {
        for (int i = 0; i < candidates.size(); i++) {
            CircuitBreaker breaker = breakers[i];
            if (!breaker.tryAcquire()) {
                log.warn("[容错] 候选 {} 熔断中，stream 跳过", candidateName(i));
                continue;
            }
            Outcome outcome = runAttempt(i, request, callback);
            switch (outcome) {
                case SWITCH -> log.warn("[容错] 候选 {} 流已隔离并切换下一候选", candidateName(i));
                default -> {
                    return; // COMPLETED / DELIVERED_ERROR：终态已交付上层
                }
            }
        }
        callback.onError(new ModelClientException(ErrorType.SERVER_ERROR, "所有模型候选均不可用（失败或熔断中）"));
    }

    /**
     * 执行单个候选的流尝试（阻塞）。结局三选一：
     * COMPLETED=正常收尾；SWITCH=首包前可降级失败/首包超时，旧流已隔离可切下一候选；
     * DELIVERED_ERROR=终态错误已通过回调交付上层（非可降级，或已出首包不切换）。
     */
    private Outcome runAttempt(int index, ChatRequest request, StreamCallback callback) {
        CircuitBreaker breaker = breakers[index];
        CountDownLatch settled = new CountDownLatch(1);
        AtomicBoolean tokenSeen = new AtomicBoolean(false);
        AtomicBoolean isolated = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<ChatUsage> usageRef = new AtomicReference<>();
        AtomicBoolean terminalSeen = new AtomicBoolean(false);

        // 包装回调：内容类增量实时转发（首包后的失败不切换，无重复风险）；
        // 终态回调（onComplete/onError）只记录不转发——是否切换由装饰器在结算时统一决定，
        // 否则失败候选的错误会先于切换决策泄漏给上层（实测单测抓出的串流形态）。
        StreamCallback wrapped = new StreamCallback() {
            private boolean blocked() {
                if (isolated.get()) {
                    log.debug("[容错] 候选 {} 已隔离，吞掉迟到的流回调（防串流）", candidateName(index));
                    return true;
                }
                return false;
            }

            @Override
            public void onContent(String delta) {
                if (blocked()) {
                    return;
                }
                tokenSeen.compareAndSet(false, true);
                callback.onContent(delta);
            }

            @Override
            public void onThinking(String delta) {
                if (blocked()) {
                    return;
                }
                tokenSeen.compareAndSet(false, true);
                callback.onThinking(delta);
            }

            @Override
            public void onToolCalls(List<ToolCall> calls) {
                if (blocked()) {
                    return;
                }
                tokenSeen.compareAndSet(false, true);
                callback.onToolCalls(calls);
            }

            @Override
            public void onComplete(String finish, ChatUsage usage) {
                if (blocked()) {
                    return;
                }
                finishReason.compareAndSet(null, finish);
                usageRef.compareAndSet(null, usage);
                terminalSeen.set(true);
                settled.countDown();
            }

            @Override
            public void onError(Throwable t) {
                if (blocked()) {
                    return;
                }
                failure.compareAndSet(null, t);
                terminalSeen.set(true);
                settled.countDown();
            }
        };

        Thread worker = new Thread(() -> {
            try {
                candidates.get(index).stream(request, wrapped);
                settled.countDown(); // stream 正常返回（回调路径通常已 countDown，此处幂等兜底）
            } catch (Throwable t) {
                if (isolated.get()) {
                    log.debug("[容错] 候选 {} 隔离后 worker 退出: {}", candidateName(index), String.valueOf(t.getMessage()));
                    return;
                }
                failure.compareAndSet(null, t);
                settled.countDown();
            }
        }, "llm-stream-attempt-" + index);
        worker.setDaemon(true);

        ScheduledFuture<?> timeoutTask = TIMEOUT_SCHEDULER.schedule(() -> {
            if (tokenSeen.get()) {
                return; // 已出首包：超时任务作废
            }
            isolated.set(true);   // 首包超时：旧流隔离 + 尽力打断（okHttp 读不保证响应，靠隔离兜底）
            worker.interrupt();
            settled.countDown();
        }, firstTokenTimeoutMs, TimeUnit.MILLISECONDS);

        worker.start();
        boolean interrupted = false;
        try {
            settled.await();
        } catch (InterruptedException e) {
            interrupted = true;
            isolated.set(true);
            worker.interrupt();
        } finally {
            timeoutTask.cancel(false);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (interrupted) {
            breaker.recordFailure();
            callback.onError(new ModelClientException(ErrorType.TIMEOUT, "流式调用被中断"));
            return Outcome.DELIVERED_ERROR;
        }

        Throwable failureCause = failure.get();
        if (!terminalSeen.get()) {
            // 死流：stream 返回（或超时）却既没说完成也没说失败——协议违规，按可降级失败处理，
            // 绝不能把零回调的死流当成正常完成（DL-8：超时/空响应触发切换）
            isolated.set(true);
            breaker.recordFailure();
            if (tokenSeen.get()) {
                callback.onError(new ModelClientException(ErrorType.NETWORK_ERROR,
                        candidateName(index) + " 流结束但未收到完成/失败终态"));
                return Outcome.DELIVERED_ERROR;
            }
            log.warn("[容错] 候选 {} 死流（无任何终态回调），切换下一候选", candidateName(index));
            return Outcome.SWITCH;
        }
        if (failureCause == null) {
            breaker.recordSuccess();
            callback.onComplete(finishReason.get(), usageRef.get());
            return Outcome.COMPLETED;
        }

        // ===== 失败路径：分类决定切换还是终态 =====
        ErrorType type;
        if (failureCause instanceof ModelClientException mce) {
            type = mce.getType();
        } else if (failureCause instanceof SocketTimeoutException) {
            type = ErrorType.TIMEOUT;
        } else {
            type = ErrorType.NETWORK_ERROR;
        }

        if (!type.isFallbackCapable()) {
            // 非可降级：不切换、不计熔断，原样交付上层
            callback.onError(wrapFailure(index, failureCause, type));
            return Outcome.DELIVERED_ERROR;
        }
        breaker.recordFailure();
        if (tokenSeen.get()) {
            // 已出首包后的流中失败：切换会让上层收到重复内容，必须按终态错误收尾
            log.warn("[容错] 候选 {} 流中失败({})，已出首包不切换", candidateName(index), type);
            callback.onError(wrapFailure(index, failureCause, type));
            return Outcome.DELIVERED_ERROR;
        }
        // 首包前可降级失败（含首包超时）：隔离旧流（onError 后的理论窗口内旧流仍可能吐增量），切换下一候选
        isolated.set(true);
        return Outcome.SWITCH;
    }

    private ModelClientException wrapFailure(int index, Throwable cause, ErrorType type) {
        if (cause instanceof ModelClientException mce) {
            return mce;
        }
        return new ModelClientException(type,
                candidateName(index) + " 流式调用失败（" + type.getLabel() + "）", cause);
    }

    private String candidateName(int index) {
        try {
            return candidates.get(index).provider();
        } catch (Exception e) {
            return "候选" + index;
        }
    }

    private enum Outcome {
        /** 正常完成，终态已交付上层。 */
        COMPLETED,
        /** 首包前可降级失败/首包超时，旧流已隔离，切换下一候选。 */
        SWITCH,
        /** 终态错误已交付上层（非可降级或已出首包），不再切换。 */
        DELIVERED_ERROR
    }

    /**
     * 每候选独立的熔断器（synchronized 状态机，多请求并发安全）。
     * CLOSED 正常放行；连续可降级失败达阈值 → OPEN openMillis；到期 → HALF_OPEN 放行
     * halfOpenAttempts 次试探（成功闭合 / 失败重开）。仅可降级失败计入。
     */
    static final class CircuitBreaker {

        private final String name;
        private final int failureThreshold;
        private final long openMillis;
        private final int halfOpenAttempts;

        private State state = State.CLOSED;
        private int consecutiveFailures;
        private long openUntil;
        private int halfOpenRemaining;

        CircuitBreaker(String name, int failureThreshold, long openMillis, int halfOpenAttempts) {
            this.name = name;
            this.failureThreshold = failureThreshold;
            this.openMillis = openMillis;
            this.halfOpenAttempts = halfOpenAttempts;
        }

        /** 是否放行本次尝试；OPEN 到期时原地转 HALF_OPEN。 */
        synchronized boolean tryAcquire() {
            if (state == State.OPEN) {
                if (System.currentTimeMillis() < openUntil) {
                    return false;
                }
                state = State.HALF_OPEN;
                halfOpenRemaining = halfOpenAttempts;
                log.warn("[熔断] {} 半开，放行 {} 次试探", name, halfOpenAttempts);
            }
            if (state == State.HALF_OPEN) {
                if (halfOpenRemaining <= 0) {
                    return false;
                }
                halfOpenRemaining--;
            }
            return true;
        }

        synchronized void recordSuccess() {
            if (state != State.CLOSED) {
                log.warn("[熔断] {} 试探成功，闭合", name);
            }
            state = State.CLOSED;
            consecutiveFailures = 0;
        }

        synchronized void recordFailure() {
            consecutiveFailures++;
            if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
                state = State.OPEN;
                openUntil = System.currentTimeMillis() + openMillis;
                log.warn("[熔断] {} 打开 {}ms（连续可降级失败 {} 次）", name, openMillis, consecutiveFailures);
            } else {
                log.warn("[熔断] {} 连续失败 {}/{}（仍关闭）", name, consecutiveFailures, failureThreshold);
            }
        }

        synchronized String describe() {
            return name + "{" + state.name() + ", 连续失败=" + consecutiveFailures + "}";
        }

        private enum State {
            CLOSED, OPEN, HALF_OPEN
        }
    }
}
