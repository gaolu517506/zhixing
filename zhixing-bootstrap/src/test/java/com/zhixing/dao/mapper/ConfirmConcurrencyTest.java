package com.zhixing.dao.mapper;

import com.zhixing.dao.entity.AgentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * resumeFromConfirm 的 CAS 并发验证（P0，真库）：两线程同抢一个 WAITING_CONFIRM 恢复名额，
 * 必须恰好一个成功——写工具的单次执行保证由 SQL 守卫承担，mock 层证明不了这一条。
 * 播种唯一 id 并在 @AfterEach 清理，不依赖既有数据形态（与 TraceControllerTest 同规）。
 */
@SpringBootTest
class ConfirmConcurrencyTest {

    @Autowired
    private AgentRunMapper runMapper;

    private long seededId;

    private long seedWaitingRun() {
        AgentRun run = new AgentRun();
        run.setId(IdGen.next());
        run.setSessionId(1L);
        run.setKbId(1L);
        run.setInput("并发验证");
        run.setStatus("WAITING_CONFIRM");
        run.setIterations(1);
        run.setMessagesSnapshot("[]");
        run.setPendingTool("{}");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run.getId();
    }

    @AfterEach
    void cleanup() {
        if (seededId != 0) {
            runMapper.deleteById(seededId);
            seededId = 0;
        }
    }

    @Test
    void 并发双confirm恰好一个抢占成功() throws Exception {
        seededId = seedWaitingRun();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return runMapper.resumeFromConfirm(seededId);
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return runMapper.resumeFromConfirm(seededId);
            });
            start.countDown();
            wins.addAndGet(first.get(10, TimeUnit.SECONDS));
            wins.addAndGet(second.get(10, TimeUnit.SECONDS));

            assertThat(wins.get()).as("两个并发 confirm 必须恰好一个 CAS 成功").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 非等待状态恢复返回零行() throws Exception {
        long id = seedWaitingRun();
        try {
            assertThat(runMapper.resumeFromConfirm(id)).isEqualTo(1);
            // 已转 RUNNING：第二次恢复必须被 CAS 拒绝（串行重复拒绝此前已覆盖，此处锁死 SQL 行为）
            assertThat(runMapper.resumeFromConfirm(id)).isZero();
        } finally {
            runMapper.deleteById(id);
        }
    }

    /** 测试内独立 id 生成，避免对 id 工具类的额外依赖。 */
    private static final class IdGen {
        private static volatile long seq = System.currentTimeMillis() * 1000;

        static synchronized long next() {
            return ++seq;
        }
    }
}
