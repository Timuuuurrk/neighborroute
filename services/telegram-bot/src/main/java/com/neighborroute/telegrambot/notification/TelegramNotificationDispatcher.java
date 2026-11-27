
package com.neighborroute.telegrambot.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;

@Component
public class TelegramNotificationDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramNotificationDispatcher.class
            );

    private final NotificationRepository repository;
    private final TelegramClient telegramClient;

    public TelegramNotificationDispatcher(
            NotificationRepository repository,
            TelegramClient telegramClient
    ) {
        this.repository = repository;
        this.telegramClient = telegramClient;
    }

    @Scheduled(fixedDelay = 5000)
    public void dispatch() {
        for (int i = 0; i < 10; i++) {
            Optional<NotificationRepository.Notification> next;

            try {
                next = repository.claimNext();
            } catch (Exception e) {
                log.error("Failed to claim notification", e);
                return;
            }

            if (next.isEmpty()) {
                return;
            }

            sendNotification(next.get());
        }
    }

    private void sendNotification(
            NotificationRepository.Notification notification
    ) {
        try {
            String message =
                    "📦 Ваш заказ доставлен!\n\n"
                            + "Заказ: " + notification.orderId()
                            + "\nГруппа: " + notification.batchId()
                            + "\n\nСовместная доставка завершена.";

            SendMessage request = SendMessage.builder()
                    .chatId(notification.telegramUserId())
                    .text(message)
                    .build();

            telegramClient.execute(request);

            repository.markSent(notification.orderId());

            log.info(
                    "Notification sent for order {}",
                    notification.orderId()
            );

        } catch (Exception e) {
            log.error("Notification delivery requires review: {}", notification.orderId(), e);

            try {
                repository.markReviewRequired(notification.orderId());
            } catch (Exception storageError) {
                log.error("Failed to persist review state for {}", notification.orderId(), storageError);
            }
        }
    }


    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void recoverStaleSending() {
        try {
            int updated =
                    repository.markStaleSendingAsReviewRequired();

            if (updated > 0) {
                log.warn(
                        "Marked {} stale notifications for review",
                        updated
                );
            }
        } catch (Exception e) {
            log.error("Failed to inspect stale notifications", e);
        }
    }

}
