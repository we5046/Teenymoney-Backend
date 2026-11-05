package com.teenyfin.teenymoney.domain.quest.concurrency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** nearest-rank 백분위: 순위 = ⌈p/100 × n⌉. 같은 원본으로 누가 계산해도 같은 값이 나온다. */
public final class Percentiles {

    private Percentiles() {
    }

    public static long nearestRank(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("백분위를 계산할 값이 없다");
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }
}
