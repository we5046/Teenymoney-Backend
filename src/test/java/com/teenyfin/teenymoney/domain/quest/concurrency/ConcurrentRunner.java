package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.teenyfin.teenymoney.global.exception.BusinessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 작업 N개를 "동시에" 시작시킨다.
 *
 * 스레드를 만들자마자 실행하면 앞 스레드가 먼저 끝나 버려 동시성이 흐려진다. 그래서 모든 스레드가
 * 준비(ready)될 때까지 기다린 뒤, 출발선(start)을 한 번에 연다. 지연은 출발선이 열린 뒤부터 잰다.
 */
public final class ConcurrentRunner {

    public record Task(String type, Callable<?> body) {
    }

    public record Outcome(String type, boolean success, String errorCode, long latencyNanos) {
    }

    private ConcurrentRunner() {
    }

    public static List<Outcome> runAtOnce(List<Task> tasks, Duration timeout) throws InterruptedException {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>(n);
        for (Task task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                long startedAt = System.nanoTime();
                try {
                    task.body().call();
                    return new Outcome(task.type(), true, null, System.nanoTime() - startedAt);
                } catch (BusinessException e) {
                    return new Outcome(task.type(), false, e.getErrorCode().getCode(), System.nanoTime() - startedAt);
                } catch (Exception e) {
                    return new Outcome(task.type(), false,
                            "UNEXPECTED:" + e.getClass().getSimpleName(), System.nanoTime() - startedAt);
                }
            }));
        }
        try {
            if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("작업 스레드가 제한 시간 안에 준비되지 않았다");
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>(n);
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
            }
            return outcomes;
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("동시 실행이 제한 시간 안에 끝나지 않았다", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
