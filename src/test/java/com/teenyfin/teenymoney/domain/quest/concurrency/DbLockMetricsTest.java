package com.teenyfin.teenymoney.domain.quest.concurrency;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.teenyfin.teenymoney.domain.quest.concurrency.QuestConcurrencyFixture.PARENT_WALLET_ID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*(localhost|127\\.0\\.0\\.1).*")
@EnabledIfEnvironmentVariable(named = "DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class DbLockMetricsTest {

    private static AnnotationConfigApplicationContext context;
    private static QuestConcurrencyFixture fixture;

    @BeforeAll
    static void open() {
        context = QuestConcurrencyContexts.open(20);
        fixture = new QuestConcurrencyFixture(context.getBean(JdbcTemplate.class));
        fixture.reset(100_000L);
    }

    @AfterAll
    static void close() {
        fixture.cleanup();
        context.close();
    }

    @Test
    @DisplayName("한 연결이 지갑 행을 잡은 동안 다른 연결이 기다리면 잠금 대기 횟수와 시간이 늘어난다")
    void capturesRowLockWait() throws Exception {
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        DataSource dataSource = context.getBean(DataSource.class);
        DbLockMetrics.Snapshot before = DbLockMetrics.read(jdbc);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockWallet(holder);
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
                try (Connection other = dataSource.getConnection()) {
                    other.setAutoCommit(false);
                    lockWallet(other);
                    other.commit();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(300);
            holder.commit();
            waiter.get(10, TimeUnit.SECONDS);
        }

        DbLockMetrics.Snapshot delta = DbLockMetrics.read(jdbc).minus(before);
        assertThat(delta.rowLockWaits()).isGreaterThanOrEqualTo(1);
        assertThat(delta.rowLockTimeMs()).isGreaterThanOrEqualTo(200);
    }

    private static void lockWallet(Connection connection) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT balance FROM T_WLT_BASE_M WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, PARENT_WALLET_ID);
            statement.executeQuery().close();
        }
    }
}
