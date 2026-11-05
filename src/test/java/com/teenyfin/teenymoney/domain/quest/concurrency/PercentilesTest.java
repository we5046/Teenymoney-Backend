package com.teenyfin.teenymoney.domain.quest.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PercentilesTest {

    @Test
    @DisplayName("nearest-rank: 1~100에서 p50=50, p95=95, p100=100")
    void nearestRankOnOneToHundred() {
        List<Long> values = LongStream.rangeClosed(1, 100).boxed().toList();
        assertThat(Percentiles.nearestRank(values, 50)).isEqualTo(50L);
        assertThat(Percentiles.nearestRank(values, 95)).isEqualTo(95L);
        assertThat(Percentiles.nearestRank(values, 100)).isEqualTo(100L);
    }

    @Test
    @DisplayName("정렬되지 않은 입력도 정렬해서 계산하고, 순위는 올림한다")
    void sortsAndRoundsUp() {
        // n=5, p95 → 순위 ⌈4.75⌉ = 5 → 최댓값
        assertThat(Percentiles.nearestRank(List.of(30L, 10L, 50L, 20L, 40L), 95)).isEqualTo(50L);
        // n=5, p50 → 순위 ⌈2.5⌉ = 3
        assertThat(Percentiles.nearestRank(List.of(30L, 10L, 50L, 20L, 40L), 50)).isEqualTo(30L);
    }

    @Test
    @DisplayName("빈 목록은 거절한다")
    void rejectsEmpty() {
        assertThatThrownBy(() -> Percentiles.nearestRank(List.of(), 50))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
