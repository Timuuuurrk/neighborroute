package com.neighborroute.orders.service;


import com.neighborroute.orders.client.BatchClient;
import com.neighborroute.orders.domain.BatchCancellationConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCancellationCoordinatorTest {

    @Mock
    private OrderCancellationTransactions transactions;

    @Mock
    private BatchClient batchClient;

    @InjectMocks
    private OrderCancellationCoordinator coordinator;

    @Test
    void shouldCancelConfirmedOrder() {
        UUID orderId = UUID.randomUUID();
        when(transactions.begin(orderId)).thenReturn(true);

        coordinator.cancel(orderId);

        InOrder inOrder = inOrder(transactions, batchClient);
        inOrder.verify(transactions).begin(orderId);
        inOrder.verify(batchClient).removeOrderFromBatch(orderId);
        inOrder.verify(transactions).complete(orderId);

        verify(transactions, never()).reject(orderId);
    }

    @Test
    void shouldRejectCancellationWhenBatchClosed() {
        UUID orderId = UUID.randomUUID();
        when(transactions.begin(orderId)).thenReturn(true);

        doThrow(new BatchCancellationConflictException(
                "Batch is closed"
        )).when(batchClient).removeOrderFromBatch(orderId);

        assertThrows(
                BatchCancellationConflictException.class,
                () -> coordinator.cancel(orderId)
        );

        verify(transactions).reject(orderId);
        verify(transactions, never()).complete(orderId);
    }

    @Test
    void shouldKeepPendingOnNetworkFailure() {
        UUID orderId = UUID.randomUUID();
        when(transactions.begin(orderId)).thenReturn(true);

        doThrow(new ResourceAccessException(
                "Connection refused"
        )).when(batchClient).removeOrderFromBatch(orderId);

        assertThrows(
                ResourceAccessException.class,
                () -> coordinator.cancel(orderId)
        );

        verify(transactions, never()).complete(orderId);
        verify(transactions, never()).reject(orderId);
    }

    @Test
    void shouldSkipAlreadyCancelledOrder() {
        UUID orderId = UUID.randomUUID();
        when(transactions.begin(orderId)).thenReturn(false);

        coordinator.cancel(orderId);

        verifyNoInteractions(batchClient);
        verify(transactions, never()).complete(orderId);
    }
}

