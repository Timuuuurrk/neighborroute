
package com.neighborroute.telegrambot.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramNotificationDispatcherTest {

    private NotificationRepository repository;
    private TelegramClient telegramClient;
    private TelegramNotificationDispatcher dispatcher;

    private final UUID orderId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        telegramClient = mock(TelegramClient.class);

        dispatcher = new TelegramNotificationDispatcher(
                repository,
                telegramClient
        );
    }

    private void prepareNotification() {
        var notification =
                new NotificationRepository.Notification(
                        orderId,
                        batchId,
                        123456L
                );

        when(repository.claimNext()).thenReturn(
                Optional.of(notification),
                Optional.empty()
        );
    }

    @Test
    void shouldMarkNotificationSentAfterSuccess()
            throws Exception {

        prepareNotification();

        dispatcher.dispatch();

        verify(telegramClient)
                .execute(any(SendMessage.class));

        verify(repository).markSent(orderId);

        verify(repository, never())
                .markReviewRequired(orderId);
    }

    @Test
    void shouldRequireReviewWhenTelegramFails()
            throws Exception {

        prepareNotification();

        when(telegramClient.execute(
                any(SendMessage.class)
        )).thenThrow(
                new RuntimeException("Simulated Telegram timeout")
        );

        dispatcher.dispatch();

        verify(repository)
                .markReviewRequired(orderId);

        verify(repository, never())
                .markSent(orderId);
    }

    @Test
    void shouldRequireReviewWhenSavingResultFails()
            throws Exception {

        prepareNotification();

        doThrow(new RuntimeException("Database unavailable"))
                .when(repository)
                .markSent(orderId);

        dispatcher.dispatch();

        verify(telegramClient)
                .execute(any(SendMessage.class));

        verify(repository)
                .markReviewRequired(orderId);
    }

    @Test
    void shouldNotSendWhenQueueIsEmpty()
            throws Exception {

        when(repository.claimNext())
                .thenReturn(Optional.empty());

        dispatcher.dispatch();

        verifyNoInteractions(telegramClient);
    }
}
