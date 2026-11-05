package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.teenyfin.teenymoney.domain.allowance.service.AllowanceService;
import com.teenyfin.teenymoney.domain.charge.service.ChargeExecutor;
import com.teenyfin.teenymoney.domain.quest.dto.request.QuestRejectRequestDTO;
import com.teenyfin.teenymoney.domain.quest.exception.QuestErrorCode;
import com.teenyfin.teenymoney.domain.quest.service.QuestReviewService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.teenyfin.teenymoney.domain.quest.concurrency.QuestConcurrencyFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 퀘스트 승인 동시 요청 정합성 판정 (설계 문서 2절, #218).
 *
 * 조건: FCM 지연 0ms, 커넥션 풀 20, 시나리오별 30회 반복. 정합성은 지연과 무관하게 지켜져야 한다.
 */
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*(localhost|127\\.0\\.0\\.1).*")
@EnabledIfEnvironmentVariable(named = "DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class QuestApprovalConcurrencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final long INITIAL_PARENT_BALANCE = 100_000L;
    /** 동시 승인·반려에서 진 요청이 받아야 하는 오류. 이 밖의 오류(UNEXPECTED 포함)는 실패다. */
    private static final Set<String> EXPECTED_LOSER_CODES = Set.of(
            QuestErrorCode.QUEST_STATUS_CONFLICT.getCode(),
            QuestErrorCode.QUEST_VERIFICATION_CONFLICT.getCode());

    private static AnnotationConfigApplicationContext context;
    private static QuestConcurrencyFixture fixture;
    private static QuestReviewService review;
    private static AllowanceService allowance;
    private static ChargeExecutor charge;
    private static DelayedFcmService fcm;

    @BeforeAll
    static void open() {
        context = QuestConcurrencyContexts.open(20);
        fixture = new QuestConcurrencyFixture(context.getBean(JdbcTemplate.class));
        review = context.getBean(QuestReviewService.class);
        allowance = context.getBean(AllowanceService.class);
        charge = context.getBean(ChargeExecutor.class);
        fcm = context.getBean(DelayedFcmService.class);
        fcm.setDelayMillis(0);
    }

    @AfterAll
    static void close() {
        fixture.cleanup();
        context.close();
    }

    @BeforeEach
    void reset() {
        fixture.reset(INITIAL_PARENT_BALANCE);
        fcm.resetCount();
    }

    @RepeatedTest(30)
    @DisplayName("S1. 같은 인증에 승인 10건이 동시에 와도 보상·점수·알림은 한 번만 남는다")
    void s1_sameVerificationApprovedConcurrently() throws Exception {
        QuestRef quest = fixture.insertPendingQuest(1_000L, true);
        List<ConcurrentRunner.Task> tasks = IntStream.range(0, 10)
                .mapToObj(i -> new ConcurrentRunner.Task("APPROVE", () -> {
                    review.approve(PARENT, quest.questId(), quest.verificationId());
                    return null;
                }))
                .toList();

        List<ConcurrentRunner.Outcome> outcomes = ConcurrentRunner.runAtOnce(tasks, TIMEOUT);

        assertThat(outcomes).filteredOn(ConcurrentRunner.Outcome::success).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.success())
                .extracting(ConcurrentRunner.Outcome::errorCode)
                .allMatch(EXPECTED_LOSER_CODES::contains);
        assertThat(fixture.rewardTransferCount(quest.questId())).isEqualTo(1);
        assertThat(fixture.balance(PARENT_WALLET_ID)).isEqualTo(INITIAL_PARENT_BALANCE - 1_000L);
        assertThat(fixture.balance(CHILD_WALLET_ID)).isEqualTo(1_000L);
        assertThat(fixture.ledgerRowsForRewardTransfer(quest.questId())).isEqualTo(2);
        assertThat(fixture.scoreEventCount(quest.questId())).isEqualTo(1);
        assertThat(fixture.notificationCount(CHILD_ID)).isEqualTo(1);
        assertThat(fcm.sendCount()).isEqualTo(1);
    }

    @RepeatedTest(30)
    @DisplayName("S2. 승인 5건과 반려 5건이 동시에 와도 하나만 확정되고 돈과 상태가 맞는다")
    void s2_approveAndRejectArriveTogether() throws Exception {
        QuestRef quest = fixture.insertPendingQuest(1_000L, true);
        List<ConcurrentRunner.Task> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(new ConcurrentRunner.Task("APPROVE", () -> {
                review.approve(PARENT, quest.questId(), quest.verificationId());
                return null;
            }));
            tasks.add(new ConcurrentRunner.Task("REJECT", () -> {
                review.reject(PARENT, quest.questId(), quest.verificationId(),
                        QuestRejectRequestDTO.builder().reason("사진을 다시 찍어 주세요").build());
                return null;
            }));
        }

        List<ConcurrentRunner.Outcome> outcomes = ConcurrentRunner.runAtOnce(tasks, TIMEOUT);

        List<ConcurrentRunner.Outcome> winners = outcomes.stream().filter(ConcurrentRunner.Outcome::success).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.success())
                .extracting(ConcurrentRunner.Outcome::errorCode)
                .allMatch(EXPECTED_LOSER_CODES::contains);

        if ("APPROVE".equals(winners.get(0).type())) {
            assertThat(fixture.questStatus(quest.questId())).isEqualTo("COMPLETED");
            assertThat(fixture.verificationStatus(quest.verificationId())).isEqualTo("APPROVED");
            assertThat(fixture.rewardTransferCount(quest.questId())).isEqualTo(1);
            assertThat(fixture.balance(PARENT_WALLET_ID)).isEqualTo(INITIAL_PARENT_BALANCE - 1_000L);
        } else {
            assertThat(fixture.questStatus(quest.questId())).isEqualTo("IN_PROGRESS");
            assertThat(fixture.verificationStatus(quest.verificationId())).isEqualTo("REJECTED");
            assertThat(fixture.remainingCount(quest.questId())).isEqualTo(2);
            assertThat(fixture.rewardTransferCount(quest.questId())).isZero();
            assertThat(fixture.balance(PARENT_WALLET_ID)).isEqualTo(INITIAL_PARENT_BALANCE);
        }
        // 어느 쪽이 이겼는지는 판정 대상이 아니다. 30회 분포는 측정 결과로만 본다.
        System.out.println("[S2] winner=" + winners.get(0).type());
    }

    /**
     * 지연 측정(ParentWalletContentionExperiment) 요청 15건: 서로 다른 퀘스트 승인 5 + 수동 용돈 5 + 충전 입금 5.
     * 퀘스트는 티니점수를 끈다. 부모 지갑 경합만 보려고 자녀 회원 행 잠금을 섞지 않는다.
     * 승인 5건 동시는 현재 UI(상세 화면 단건 승인)에서 생기지 않는 조건이라 판정 테스트에서는 뺐다(#223, #222).
     */
    static List<ConcurrentRunner.Task> parentWalletContentionTasks(QuestConcurrencyFixture fixture,
                                                                   QuestReviewService review,
                                                                   AllowanceService allowance,
                                                                   ChargeExecutor charge) {
        List<ConcurrentRunner.Task> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            QuestRef quest = fixture.insertPendingQuest(1_000L, false);
            long chargeId = fixture.insertPendingCharge(3_000L);
            String allowanceKey = UUID.randomUUID().toString();
            tasks.add(new ConcurrentRunner.Task("APPROVE", () -> {
                review.approve(PARENT, quest.questId(), quest.verificationId());
                return null;
            }));
            tasks.add(new ConcurrentRunner.Task("ALLOWANCE", () ->
                    allowance.sendAllowance(PARENT, CHILD_ID, 2_000L, allowanceKey)));
            tasks.add(new ConcurrentRunner.Task("CHARGE", () ->
                    charge.lockAndCredit(chargeId, "concurrency-payment-key-" + chargeId)));
        }
        return tasks;
    }
}
