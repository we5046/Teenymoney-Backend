package com.teenyfin.teenymoney.domain.quest.concurrency;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 측정 결과 파일 4개를 쓴다(설계 문서 6.1절).
 * raw.csv: 요청 1건, runs.csv: 회차 1번, env.txt: 실행 환경, summary.md: 조건별 집계.
 * 준비 실행(warmup)은 원본에 남기고 집계에서만 뺀다.
 */
public class ExperimentReport {

    public record RequestRow(int poolSize, long fcmDelayMs, int run, boolean warmup, String type,
                             boolean success, String errorCode, double latencyMs) {
    }

    public record RunRow(int poolSize, long fcmDelayMs, int run, boolean warmup, long rowLockWaits,
                         long rowLockTimeMs, long deadlocks, int maxAwaitingConnections, boolean invariantsHeld) {
    }

    private final Path dir;
    private final List<RequestRow> requests = new ArrayList<>();
    private final List<RunRow> runs = new ArrayList<>();

    public ExperimentReport(Path dir) {
        this.dir = dir;
    }

    public void add(RequestRow row) {
        requests.add(row);
    }

    public void add(RunRow row) {
        runs.add(row);
    }

    public void writeAll(JdbcTemplate jdbc) throws IOException {
        Files.createDirectories(dir);
        writeRaw();
        writeRuns();
        writeEnv(jdbc);
        writeSummary();
    }

    private void writeRaw() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("pool_size,fcm_delay_ms,run,warmup,type,success,error_code,latency_ms");
        for (RequestRow r : requests) {
            lines.add(String.format(Locale.ROOT, "%d,%d,%d,%b,%s,%b,%s,%.3f", r.poolSize(), r.fcmDelayMs(), r.run(),
                    r.warmup(), r.type(), r.success(), r.errorCode() == null ? "" : r.errorCode(), r.latencyMs()));
        }
        Files.write(dir.resolve("raw.csv"), lines, StandardCharsets.UTF_8);
    }

    private void writeRuns() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("pool_size,fcm_delay_ms,run,warmup,row_lock_waits,row_lock_time_ms,deadlocks,max_awaiting_connections,invariants_held");
        for (RunRow r : runs) {
            lines.add(String.format(Locale.ROOT, "%d,%d,%d,%b,%d,%d,%d,%d,%b", r.poolSize(), r.fcmDelayMs(), r.run(),
                    r.warmup(), r.rowLockWaits(), r.rowLockTimeMs(), r.deadlocks(), r.maxAwaitingConnections(),
                    r.invariantsHeld()));
        }
        Files.write(dir.resolve("runs.csv"), lines, StandardCharsets.UTF_8);
    }

    private void writeEnv(JdbcTemplate jdbc) throws IOException {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        List<String> lines = List.of(
                "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"),
                "cpu_logical_cores=" + Runtime.getRuntime().availableProcessors(),
                "memory_total_mb=" + os.getTotalMemorySize() / 1024 / 1024,
                "jdk=" + System.getProperty("java.version"),
                "mysql=" + jdbc.queryForObject("SELECT VERSION()", String.class),
                "jdbc_driver=" + System.getenv().getOrDefault("DB_DRIVER", "(application.properties 기본값)"),
                "git_commit=" + git("rev-parse", "HEAD"),
                "git_dirty_files=" + git("status", "--porcelain").lines().count(),
                "percentile_method=nearest-rank (rank = ceil(p/100 * n))",
                "warmup_runs_excluded_from_summary=true");
        Files.write(dir.resolve("env.txt"), lines, StandardCharsets.UTF_8);
    }

    private void writeSummary() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# 부모 지갑 경합 지연 요약");
        lines.add("");
        lines.add("준비 실행 제외. 백분위는 nearest-rank. 단위 ms.");
        lines.add("");
        lines.add("| 풀 | FCM 지연 | 종류 | n | p50 | p95 | 최대 |");
        lines.add("|---|---|---|---|---|---|---|");
        Map<String, List<Long>> latency = new TreeMap<>();
        for (RequestRow r : requests) {
            if (!r.warmup() && r.success()) {
                latency.computeIfAbsent(key(r.poolSize(), r.fcmDelayMs(), r.type()), k -> new ArrayList<>())
                        .add(Math.round(r.latencyMs()));
            }
        }
        latency.forEach((k, values) -> {
            String[] p = k.split("\\|");
            lines.add(String.format("| %s | %s | %s | %d | %d | %d | %d |", p[0], p[1], p[2], values.size(),
                    Percentiles.nearestRank(values, 50), Percentiles.nearestRank(values, 95),
                    Percentiles.nearestRank(values, 100)));
        });
        lines.add("");
        lines.add("| 풀 | FCM 지연 | 회차 | 잠금 대기 평균(회) | 잠금 시간 평균(ms) | 데드락 합계 | 최대 커넥션 대기 | 정합성 통과 |");
        lines.add("|---|---|---|---|---|---|---|---|");
        Map<String, List<RunRow>> byCondition = runs.stream().filter(r -> !r.warmup())
                .collect(Collectors.groupingBy(r -> r.poolSize() + "|" + r.fcmDelayMs(), TreeMap::new, Collectors.toList()));
        byCondition.forEach((k, rows) -> {
            String[] p = k.split("\\|");
            lines.add(String.format(Locale.ROOT, "| %s | %s | %d | %.1f | %.1f | %d | %d | %d/%d |", p[0], p[1], rows.size(),
                    rows.stream().mapToLong(RunRow::rowLockWaits).average().orElse(0),
                    rows.stream().mapToLong(RunRow::rowLockTimeMs).average().orElse(0),
                    rows.stream().mapToLong(r -> Math.max(r.deadlocks(), 0)).sum(),
                    rows.stream().mapToInt(RunRow::maxAwaitingConnections).max().orElse(0),
                    rows.stream().filter(RunRow::invariantsHeld).count(), rows.size()));
        });
        lines.add("");
        lines.add("## FCM 0ms 대비 300ms p95 차이 (ms)");
        lines.add("");
        lines.add("| 풀 | 종류 | p95(0ms) | p95(300ms) | 차이 |");
        lines.add("|---|---|---|---|---|");
        for (int pool : new int[]{20, 10}) {
            for (String type : new String[]{"APPROVE", "ALLOWANCE", "CHARGE"}) {
                List<Long> base = latency.get(key(pool, 0, type));
                List<Long> slow = latency.get(key(pool, 300, type));
                if (base != null && slow != null) {
                    long b = Percentiles.nearestRank(base, 95);
                    long s = Percentiles.nearestRank(slow, 95);
                    lines.add(String.format("| %d | %s | %d | %d | %+d |", pool, type, b, s, s - b));
                }
            }
        }
        lines.add("");
        lines.add("CHARGE는 알림을 보내지 않으므로 지연 증가분이 잠금·커넥션 대기만 반영한다(주 지표).");
        lines.add("ALLOWANCE는 커밋 뒤 자기 알림 FCM도 기다리므로, 증가분에 자기 FCM 지연이 섞인다.");
        lines.add("가설 판정은 이 표를 보고 결과 문서(docs/QUEST_CONCURRENCY_EXPERIMENT.md)에 사람이 쓴다.");
        Files.write(dir.resolve("summary.md"), lines, StandardCharsets.UTF_8);
    }

    private static String key(int pool, long delay, String type) {
        return pool + "|" + delay + "|" + type;
    }

    private static String git(String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            return out;
        } catch (IOException e) {
            return "unavailable: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}
