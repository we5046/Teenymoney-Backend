package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.google.firebase.messaging.Message;
import com.teenyfin.teenymoney.domain.notification.service.FcmService;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 실제 FCM 대신 쓰는 가짜 객체.
 *
 * 승인 트랜잭션 안에서 FirebaseMessaging.send()를 동기로 부르는 구조(#219)를 재현하려고,
 * 네트워크 호출 대신 지정한 시간만큼 이 스레드를 멈춘다. createMessage()는 그대로 쓴다.
 */
public class DelayedFcmService extends FcmService {

    private volatile long delayMillis;
    private final AtomicInteger sendCount = new AtomicInteger();

    public void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    public int sendCount() {
        return sendCount.get();
    }

    public void resetCount() {
        sendCount.set(0);
    }

    @Override
    public void send(Message message) {
        sendCount.incrementAndGet();
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
