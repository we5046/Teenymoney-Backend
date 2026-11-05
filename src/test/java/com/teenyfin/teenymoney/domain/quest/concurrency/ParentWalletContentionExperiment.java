package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.teenyfin.teenymoney.domain.allowance.service.AllowanceService;
import com.teenyfin.teenymoney.domain.charge.service.ChargeExecutor;
import com.teenyfin.teenymoney.domain.quest.service.QuestReviewService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.teenyfin.teenymoney.domain.quest.concurrency.QuestConcurrencyFixture.*;

/**
 * S3 지연 측정 (설계 문서 4절). 평소 테스트에서는 돌지 않는다: RUN_EXPERIMENT=true일 때만 실행한다.
 *
 * 조건: 풀 {20, 10} × FCM 지연 {0, 100, 300}ms. 조건마다 준비 3회 + 본 실행 30회.
 * 결과: build/experiments/quest-concurrency/{실행 시각}/
 */
@EnabledIfEnvironmentVariable(named = "RUN_EXPERIMENT", matches = "true")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*(localhost|127\\.0\\.0\\.1).*")
@EnabledIfEnvironmentVariable(named = "DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class ParentWalletContentionExperiment {

    private static final int[] POOL_SIZES = {20, 10};
    private static final long[] FCM_DELAYS_MS = {0, 100, 300};
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 30;
    private static final long INITIAL_PARENT_BALANCE = 100_000L;
    private static final long EXPECTED_PARENT_BALANCE = INITIAL_PARENT_BALANCE + 5 * 3_000L - 5 * 1_000L - 5 * 2_000L;
    private static final long EXPECTED_CHILD_BALANCE = 5 * 1_000L + 5 * 2_000L;

    @Test
    @DisplayName("부모 지갑 경합 지연을 풀·FCM 지연 조건별로 측정해 build/experiments에 남긴다")
    void measure() throws Exception {
        Path dir = Path.of("build", "experiments", "quest-concurrency",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        ExperimentReport report = new ExperimentReport(dir);
        JdbcTemplate lastJdbc = null;

        for (int poolSize : POOL_SIZES) {
            try (AnnotationConfigApplicationContext context = QuestConcurrencyContexts.open(poolSize)) {
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                QuestConcurrencyFixture fixture = new QuestConcurrencyFixture(jdbc);
                QuestReviewService review = context.getBean(QuestReviewService.class);
                AllowanceService allowance = context.getBean(AllowanceService.class);
                ChargeExecutor charge = context.getBean(ChargeExecutor.class);
                DelayedFcmService fcm = context.getBean(DelayedFcmService.class);

                for (long delay : FCM_DELAYS_MS) {
                    fcm.setDelayMillis(delay);
                    for (int run = 1; run <= WARMUP_RUNS + MEASURED_RUNS; run++) {
                        boolean warmup = run <= WARMUP_RUNS;
                        fixture.reset(INITIAL_PARENT_BALANCE);
                        List<ConcurrentRunner.Task> tasks =
                                QuestApprovalConcurrencyTest.parentWalletContentionTasks(fixture, review, allowance, charge);

                        DbLockMetrics.Snapshot before = DbLockMetrics.read(jdbc);
                        AtomicInteger maxAwaiting = new AtomicInteger();
                        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
                        // 5ms마다 풀에서 커넥션을 기다리는 스레드 수를 보고 최댓값을 남긴다.
                        sampler.scheduleAtFixedRate(() -> maxAwaiting.accumulateAndGet(
                                dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max),
                                0, 5, TimeUnit.MILLISECONDS);
                        List<ConcurrentRunner.Outcome> outcomes;
                        try {
                            outcomes = ConcurrentRunner.runAtOnce(tasks, Duration.ofSeconds(60));
                        } finally {
                            sampler.shutdownNow();
                        }
                        DbLockMetrics.Snapshot delta = DbLockMetrics.read(jdbc).minus(before);

                        boolean invariantsHeld = outcomes.stream().allMatch(ConcurrentRunner.Outcome::success)
                                && fixture.balance(PARENT_WALLET_ID) == EXPECTED_PARENT_BALANCE
                                && fixture.balance(CHILD_WALLET_ID) == EXPECTED_CHILD_BALANCE
                                && fixture.ledgerNet(PARENT_WALLET_ID) == EXPECTED_PARENT_BALANCE - INITIAL_PARENT_BALANCE;

                        for (ConcurrentRunner.Outcome o : outcomes) {
                            report.add(new ExperimentReport.RequestRow(poolSize, delay, run, warmup, o.type(),
                                    o.success(), o.errorCode(), o.latencyNanos() / 1_000_000.0));
                        }
                        report.add(new ExperimentReport.RunRow(poolSize, delay, run, warmup, delta.rowLockWaits(),
                                delta.rowLockTimeMs(), delta.deadlocks(), maxAwaiting.get(), invariantsHeld));
                    }
                }
                fixture.cleanup();
                report.writeAll(jdbc);
                lastJdbc = jdbc;
            }
        }
        System.out.println("[experiment] 결과: " + dir.toAbsolutePath());
        if (lastJdbc == null) {
            throw new IllegalStateException("측정이 한 번도 실행되지 않았다");
        }
    }
}
