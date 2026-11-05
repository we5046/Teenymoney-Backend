package com.teenyfin.teenymoney.domain.quest.concurrency;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * MySQL 잠금 지표 스냅샷. 서버 전역 값이라 다른 작업이 없는 로컬 DB에서만 의미가 있다.
 *
 * - Innodb_row_lock_waits / Innodb_row_lock_time(ms): SHOW GLOBAL STATUS
 * - lock_deadlocks: information_schema.INNODB_METRICS. 꺼져 있으면 -1로 둔다.
 */
public final class DbLockMetrics {

    public record Snapshot(long rowLockWaits, long rowLockTimeMs, long deadlocks) {
        public Snapshot minus(Snapshot before) {
            long deadlockDelta = deadlocks < 0 || before.deadlocks < 0 ? -1 : deadlocks - before.deadlocks;
            return new Snapshot(rowLockWaits - before.rowLockWaits, rowLockTimeMs - before.rowLockTimeMs, deadlockDelta);
        }
    }

    private DbLockMetrics() {
    }

    public static Snapshot read(JdbcTemplate jdbc) {
        Map<String, Long> status = new HashMap<>();
        jdbc.query("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits', 'Innodb_row_lock_time')",
                rs -> {
                    status.put(rs.getString(1), rs.getLong(2));
                });
        Long deadlocks = jdbc.query(
                "SELECT `COUNT`, STATUS FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'",
                rs -> rs.next() && "enabled".equalsIgnoreCase(rs.getString(2)) ? rs.getLong(1) : -1L);
        return new Snapshot(
                status.getOrDefault("Innodb_row_lock_waits", 0L),
                status.getOrDefault("Innodb_row_lock_time", 0L),
                deadlocks == null ? -1L : deadlocks);
    }
}
