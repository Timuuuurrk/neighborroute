package com.neighborroute.orders.service;


import com.neighborroute.orders.client.BatchClient;
import com.neighborroute.orders.domain.BatchCancellationConflictException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderCancellationCoordinator {

    private final OrderCancellationTransactions transactions;
    private final BatchClient batchClient;

    public OrderCancellationCoordinator(
            OrderCancellationTransactions transactions,
            BatchClient batchClient
    ) {
        this.transactions = transactions;
        this.batchClient = batchClient;
    }

    public void cancel(UUID orderId) {
        boolean started = transactions.begin(orderId);

        if (!started) {
            return;
        }

        try {
            batchClient.removeOrderFromBatch(orderId);
        } catch (BatchCancellationConflictException ex) {
            transactions.reject(orderId);
            throw ex;
        }

        transactions.complete(orderId);
    }
}
