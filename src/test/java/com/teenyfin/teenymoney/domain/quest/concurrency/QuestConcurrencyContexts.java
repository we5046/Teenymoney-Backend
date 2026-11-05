package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.teenyfin.teenymoney.domain.allowance.service.AllowanceService;
import com.teenyfin.teenymoney.domain.charge.service.ChargeExecutor;
import com.teenyfin.teenymoney.domain.family.service.FamilyAccessService;
import com.teenyfin.teenymoney.domain.quest.service.QuestReviewService;
import com.teenyfin.teenymoney.domain.teenyscore.service.TeenyScoreChangeService;
import com.teenyfin.teenymoney.domain.teenyscore.service.TeenyScorePolicyService;
import com.teenyfin.teenymoney.domain.wallet.service.TransferExecutor;
import com.teenyfin.teenymoney.domain.wallet.service.TransferFailureRecorder;
import com.teenyfin.teenymoney.domain.wallet.service.TransferService;
import com.teenyfin.teenymoney.domain.wallet.service.WalletLedgerService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 풀 크기마다 컨텍스트를 새로 연다. SpringExtension은 컨텍스트를 캐시해서 같은 테스트 안에서
 * 풀 크기를 바꿀 수 없으므로 코드로 직접 연다.
 *
 * 패키지 스캔 대신 빈을 하나씩 적는다. QuestFlowIntegrationTest 주석에 적힌 대로, 스캔하면
 * 테스트용 @Configuration이 섞여 들어와 실제 빈을 목으로 덮어쓸 수 있다.
 */
public final class QuestConcurrencyContexts {

    static final Class<?>[] BEANS = {
            QuestConcurrencyTestConfig.class,
            QuestReviewService.class,
            TransferService.class,
            TransferExecutor.class,
            TransferFailureRecorder.class,
            WalletLedgerService.class,
            TeenyScorePolicyService.class,
            TeenyScoreChangeService.class,
            FamilyAccessService.class,
            AllowanceService.class,
            ChargeExecutor.class
    };

    private QuestConcurrencyContexts() {
    }

    public static AnnotationConfigApplicationContext open(int poolSize) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("concurrency", Map.of("concurrency.pool.size", poolSize)));
        context.register(BEANS);
        context.refresh();
        return context;
    }
}
