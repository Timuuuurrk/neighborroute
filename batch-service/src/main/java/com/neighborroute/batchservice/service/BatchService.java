package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.client.OrderClient;
import com.neighborroute.batchservice.client.OrderDetails;
import com.neighborroute.batchservice.domain.Batch;
import com.neighborroute.batchservice.domain.BatchOrderStatus;
import com.neighborroute.batchservice.domain.BatchStatus;
import com.neighborroute.batchservice.exception.OrderAlreadyInBatchException;
import com.neighborroute.batchservice.exception.OrderNotConfirmedException;
import com.neighborroute.batchservice.repository.BatchOrderRepository;
import com.neighborroute.batchservice.repository.BatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class BatchService {

    private final OrderClient orderClient;

    private final BatchRepository batchRepository;

    private final BatchOrderRepository batchOrderRepository;

    public BatchService(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @Transactional
    public void joinBatch(UUID orderId) {
        OrderDetails order = orderClient.getOrder(orderId);

        if (!"CONFIRMED".equals(order.status())) {
            throw new OrderNotConfirmedException(orderId);
        }

        if (batchOrderRepository.existsByOrderIdAndStatus(
                orderId, BatchOrderStatus.ACTIVE)) {
            throw new OrderAlreadyInBatchException(orderId);
        }


        Instant now = Instant.now();

        Batch batch = batchRepository
                .findFirstByStoreIdAndDeliveryBuildingAndStatusAndExpiresAtAfterOrderByCreatedAtAsc(
                        order.storeId(),
                        order.deliveryBuilding(),
                        BatchStatus.OPEN,
                        now
                )
                .orElseGet(() -> batchRepository.save(
                        new Batch(order.storeId(), order.deliveryBuilding(),
                                now, Duration.ofMinutes(10))));


    }
}