package com.neighborroute.orders.scheduler;


import com.neighborroute.orders.domain.Order;
import com.neighborroute.orders.domain.OrderStatus;
import com.neighborroute.orders.repository.OrderRepository;
import com.neighborroute.orders.service.OrderCancellationCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCancellationRecoverySchedulerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderCancellationCoordinator coordinator;

    @Mock
    private Order order1;

    @Mock
    private Order order2;

    @InjectMocks
    private OrderCancellationRecoveryScheduler scheduler;

    @Test
    void shouldRetryPendingCancellations() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(order1.getId()).thenReturn(id1);
        when(order2.getId()).thenReturn(id2);

        when(orderRepository
                .findTop50ByStatusOrderByCreatedAtAsc(
                        OrderStatus.CANCEL_PENDING
                ))
                .thenReturn(List.of(order1, order2));

        scheduler.recoverPendingCancellations();

        verify(coordinator).cancel(id1);
        verify(coordinator).cancel(id2);
    }

    @Test
    void shouldContinueAfterFailedCancellation() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(order1.getId()).thenReturn(id1);
        when(order2.getId()).thenReturn(id2);

        when(orderRepository
                .findTop50ByStatusOrderByCreatedAtAsc(
                        OrderStatus.CANCEL_PENDING
                ))
                .thenReturn(List.of(order1, order2));

        doThrow(new RuntimeException("Ошибка сети"))
                .when(coordinator).cancel(id1);

        scheduler.recoverPendingCancellations();

        verify(coordinator).cancel(id1);
        verify(coordinator).cancel(id2);
    }
}
