package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.teenyfin.teenymoney.domain.quest.exception.QuestErrorCode;
import com.teenyfin.teenymoney.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentRunnerTest {

    @Test
    @DisplayName("모든 작업이 동시에 실행 중인 상태가 된다")
    void startsAllTasksTogether() throws Exception {
        int n = 8;
        // 8개가 모두 동시에 이 지점에 와야 통과한다. 순차 실행이면 첫 작업이 2초 뒤 타임아웃으로 실패한다.
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<ConcurrentRunner.Task> tasks = IntStream.range(0, n)
                .mapToObj(i -> new ConcurrentRunner.Task("T", () -> barrier.await(2, TimeUnit.SECONDS)))
                .toList();

        List<ConcurrentRunner.Outcome> outcomes = ConcurrentRunner.runAtOnce(tasks, Duration.ofSeconds(10));

        assertThat(outcomes).hasSize(n).allMatch(ConcurrentRunner.Outcome::success);
    }

    @Test
    @DisplayName("업무 예외는 오류 코드로, 그 밖의 예외는 UNEXPECTED로 분류한다")
    void classifiesFailures() throws Exception {
        List<ConcurrentRunner.Task> tasks = List.of(
                new ConcurrentRunner.Task("OK", () -> "done"),
                new ConcurrentRunner.Task("BIZ", () -> {
                    throw new BusinessException(QuestErrorCode.QUEST_STATUS_CONFLICT);
                }),
                new ConcurrentRunner.Task("BOOM", () -> {
                    throw new IllegalStateException("boom");
                }));

        List<ConcurrentRunner.Outcome> outcomes = ConcurrentRunner.runAtOnce(tasks, Duration.ofSeconds(10));

        assertThat(outcomes).extracting(ConcurrentRunner.Outcome::type).containsExactly("OK", "BIZ", "BOOM");
        assertThat(outcomes.get(0).success()).isTrue();
        assertThat(outcomes.get(1).errorCode()).isEqualTo(QuestErrorCode.QUEST_STATUS_CONFLICT.getCode());
        assertThat(outcomes.get(2).errorCode()).isEqualTo("UNEXPECTED:IllegalStateException");
        assertThat(outcomes).allMatch(o -> o.latencyNanos() >= 0);
    }
}
