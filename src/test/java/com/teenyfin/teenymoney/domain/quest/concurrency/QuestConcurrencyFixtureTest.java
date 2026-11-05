package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.teenyfin.teenymoney.domain.quest.service.QuestReviewService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.teenyfin.teenymoney.domain.quest.concurrency.QuestConcurrencyFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".*(localhost|127\\.0\\.0\\.1).*")
@EnabledIfEnvironmentVariable(named = "DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class QuestConcurrencyFixtureTest {

    private static AnnotationConfigApplicationContext context;
    private static QuestConcurrencyFixture fixture;

    @BeforeAll
    static void open() {
        context = QuestConcurrencyContexts.open(20);
        fixture = new QuestConcurrencyFixture(context.getBean(JdbcTemplate.class));
    }

    @AfterAll
    static void close() {
        fixture.cleanup();
        context.close();
    }

    @Test
    @DisplayName("픽스처로 만든 승인 대기 퀘스트를 단건 승인하면 보상·점수·알림·푸시가 한 번씩 남는다")
    void approvesSingleQuest() {
        DelayedFcmService fcm = context.getBean(DelayedFcmService.class);
        fixture.reset(100_000L);
        fcm.resetCount();
        QuestRef quest = fixture.insertPendingQuest(1_000L, true);

        context.getBean(QuestReviewService.class).approve(PARENT, quest.questId(), quest.verificationId());

        assertThat(fixture.questStatus(quest.questId())).isEqualTo("COMPLETED");
        assertThat(fixture.balance(PARENT_WALLET_ID)).isEqualTo(99_000L);
        assertThat(fixture.balance(CHILD_WALLET_ID)).isEqualTo(1_000L);
        assertThat(fixture.rewardTransferCount(quest.questId())).isEqualTo(1);
        assertThat(fixture.ledgerRowsForRewardTransfer(quest.questId())).isEqualTo(2);
        assertThat(fixture.scoreEventCount(quest.questId())).isEqualTo(1);
        assertThat(fixture.notificationCount(CHILD_ID)).isEqualTo(1);
        // 자녀에게 FCM 토큰과 퀘스트 알림 수신이 켜져 있어 푸시 경로를 실제로 탄다.
        assertThat(fcm.sendCount()).isEqualTo(1);
    }
}
