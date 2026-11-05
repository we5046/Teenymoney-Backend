package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.teenyfin.teenymoney.global.security.MemberPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 동시성 측정용 테스트 데이터.
 *
 * 회원은 900021~ 대역, 지갑·연동·결제수단은 음수 ID를 쓴다. QuestFlowIntegrationTest(900011~)와
 * 운영·시드 데이터와 겹치지 않는다. 퀘스트·인증·충전은 회차마다 개수가 달라 자동 증가 ID를 쓰고,
 * 정리는 부모·지갑 ID 기준으로 한다.
 *
 * 퀘스트는 생성 API를 거치지 않고 PENDING 상태로 바로 넣는다. 측정 대상이 승인 구간뿐이다.
 */
public class QuestConcurrencyFixture {

    public static final long PARENT_ID = 900021L;
    public static final long CHILD_ID = 900022L;
    public static final long PARENT_WALLET_ID = -900021L;
    public static final long CHILD_WALLET_ID = -900022L;
    public static final long CONNECTION_ID = -900021L;
    public static final long PAYMENT_METHOD_ID = -900021L;
    public static final MemberPrincipal PARENT = new MemberPrincipal(PARENT_ID, "PARENT");

    /** 고정 시계(2000-01-02 10:00 KST)보다 뒤라 반려 시 다시 수행 상태로 열린다. */
    private static final String DEADLINE = "2000-01-03 10:00:00";

    public record QuestRef(long questId, long verificationId) {
    }

    private final JdbcTemplate jdbc;

    public QuestConcurrencyFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void reset(long parentBalance) {
        cleanup();
        // 둘 다 FCM 토큰이 있고 퀘스트 알림을 켜 둔다(컬럼 기본값 TRUE). 푸시 경로를 실제로 타게 하기 위해서다.
        jdbc.update("INSERT INTO T_MBR_INFO_M (id, role, name, birth_date, phone_number, email, password, teeny_score, fcm_token, status) "
                + "VALUES (?, 'PARENT', '동시성부모', '1985-01-01', '01090000021', 'concurrency-parent@test.local', 'test-password', NULL, 'fake-fcm-parent', 'ACTIVE'), "
                + "(?, 'CHILD', '동시성자녀', '2013-01-01', '01090000022', 'concurrency-child@test.local', 'test-password', 600, 'fake-fcm-child', 'ACTIVE')",
                PARENT_ID, CHILD_ID);
        jdbc.update("INSERT INTO T_MBR_CONN_R (id, parent_id, child_id, status, created_at) VALUES (?, ?, ?, 'ACTIVE', '2000-01-01 09:00:00')",
                CONNECTION_ID, PARENT_ID, CHILD_ID);
        jdbc.update("INSERT INTO T_WLT_BASE_M (id, member_id, balance, type, created_at) VALUES "
                + "(?, ?, ?, 'MEMBER', '2000-01-01 09:00:00'), (?, ?, 0, 'MEMBER', '2000-01-01 09:00:00')",
                PARENT_WALLET_ID, PARENT_ID, parentBalance, CHILD_WALLET_ID, CHILD_ID);
        jdbc.update("INSERT INTO T_PAY_METHOD_M (id, parent_id, billing_key, type, card_company, masked_card_number, is_primary, status) "
                + "VALUES (?, ?, 'concurrency-billing-key', 'CARD', '테스트카드', '0000-00**-****-0000', TRUE, 'ACTIVE')",
                PAYMENT_METHOD_ID, PARENT_ID);
    }

    public QuestRef insertPendingQuest(long reward, boolean teenyScore) {
        long questId = insertReturningKey(
                "INSERT INTO T_QST_BASE_M (parent_id, child_id, creation_request_key, title, content, deadline, "
                        + "is_teeny_score, reward_amount, status, remaining_count) "
                        + "VALUES (?, ?, UUID(), '동시성 측정 퀘스트', '동시성 측정', ?, ?, ?, 'PENDING', 3)",
                PARENT_ID, CHILD_ID, DEADLINE, teenyScore, reward);
        long verificationId = insertReturningKey(
                "INSERT INTO T_QST_VERIFY_L (quest_id, attempt_no, content, status) VALUES (?, 1, '다 했어요', 'PENDING')",
                questId);
        return new QuestRef(questId, verificationId);
    }

    public long insertPendingCharge(long amount) {
        return insertReturningKey(
                "INSERT INTO T_WLT_CHARGE_L (wallet_id, payment_method_id, idempotency_key, amount, status, order_id) "
                        + "VALUES (?, ?, UUID(), ?, 'PENDING', UUID())",
                PARENT_WALLET_ID, PAYMENT_METHOD_ID, amount);
    }

    public long balance(long walletId) {
        return jdbc.queryForObject("SELECT balance FROM T_WLT_BASE_M WHERE id = ?", Long.class, walletId);
    }

    public int rewardTransferCount(long questId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM T_WLT_TRF_L WHERE idempotency_key = ? AND status = 'COMPLETED'",
                Integer.class, "QUEST_REWARD:" + questId);
    }

    public int ledgerRowsForRewardTransfer(long questId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM T_WLT_HIST_H h JOIN T_WLT_TRF_L t ON t.id = h.transfer_id "
                + "WHERE t.idempotency_key = ?", Integer.class, "QUEST_REWARD:" + questId);
    }

    public int scoreEventCount(long questId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM T_TNY_SCOREHIST_H WHERE child_id = ? AND event_key = ?",
                Integer.class, CHILD_ID, "QUEST_COMPLETED:" + questId);
    }

    public int notificationCount(long memberId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM T_NTF_NOTI_L WHERE member_id = ?", Integer.class, memberId);
    }

    public String questStatus(long questId) {
        return jdbc.queryForObject("SELECT status FROM T_QST_BASE_M WHERE id = ?", String.class, questId);
    }

    public int remainingCount(long questId) {
        return jdbc.queryForObject("SELECT remaining_count FROM T_QST_BASE_M WHERE id = ?", Integer.class, questId);
    }

    public String verificationStatus(long verificationId) {
        return jdbc.queryForObject("SELECT status FROM T_QST_VERIFY_L WHERE id = ?", String.class, verificationId);
    }

    /** 원장 기준 순변동: 입금 합계 − 출금 합계. */
    public long ledgerNet(long walletId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) "
                + "FROM T_WLT_HIST_H WHERE wallet_id = ?", Long.class, walletId);
    }

    public long minBalanceAfter(long walletId) {
        return jdbc.queryForObject("SELECT COALESCE(MIN(balance_after), 0) FROM T_WLT_HIST_H WHERE wallet_id = ?",
                Long.class, walletId);
    }

    /** 외래 키 순서대로 지운다: 원장 → 송금·충전 → 결제수단 → 알림·점수 → 인증 → 퀘스트 → 지갑 → 연동 → 회원. */
    public void cleanup() {
        jdbc.update("DELETE FROM T_WLT_HIST_H WHERE wallet_id IN (?, ?)", PARENT_WALLET_ID, CHILD_WALLET_ID);
        jdbc.update("DELETE FROM T_WLT_TRF_L WHERE from_wallet_id IN (?, ?) OR to_wallet_id IN (?, ?)",
                PARENT_WALLET_ID, CHILD_WALLET_ID, PARENT_WALLET_ID, CHILD_WALLET_ID);
        jdbc.update("DELETE FROM T_WLT_CHARGE_L WHERE wallet_id IN (?, ?)", PARENT_WALLET_ID, CHILD_WALLET_ID);
        jdbc.update("DELETE FROM T_PAY_METHOD_M WHERE id = ?", PAYMENT_METHOD_ID);
        jdbc.update("DELETE FROM T_NTF_NOTI_L WHERE member_id IN (?, ?)", PARENT_ID, CHILD_ID);
        jdbc.update("DELETE FROM T_TNY_SCOREHIST_H WHERE child_id = ?", CHILD_ID);
        jdbc.update("DELETE FROM T_QST_VERIFY_L WHERE quest_id IN (SELECT id FROM (SELECT id FROM T_QST_BASE_M WHERE parent_id = ?) q)",
                PARENT_ID);
        jdbc.update("DELETE FROM T_QST_BASE_M WHERE parent_id = ?", PARENT_ID);
        jdbc.update("DELETE FROM T_WLT_BASE_M WHERE id IN (?, ?)", PARENT_WALLET_ID, CHILD_WALLET_ID);
        jdbc.update("DELETE FROM T_MBR_CONN_R WHERE parent_id = ?", PARENT_ID);
        jdbc.update("DELETE FROM T_MBR_INFO_M WHERE id IN (?, ?)", PARENT_ID, CHILD_ID);
    }

    private long insertReturningKey(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
