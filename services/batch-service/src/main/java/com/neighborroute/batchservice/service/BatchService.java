package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.cache.OpenBatchCacheInvalidator;
import com.neighborroute.batchservice.client.OrderClient;
import com.neighborroute.batchservice.client.OrderDetails;
import com.neighborroute.batchservice.domain.Batch;
import com.neighborroute.batchservice.domain.BatchOrder;
import com.neighborroute.batchservice.domain.BatchOrderStatus;
import com.neighborroute.batchservice.domain.BatchStatus;
import com.neighborroute.batchservice.exception.BatchMembershipChangedException;
import com.neighborroute.batchservice.exception.BatchNotOpenException;
import com.neighborroute.batchservice.exception.OrderAlreadyInBatchException;
import com.neighborroute.batchservice.exception.OrderNotConfirmedException;
import com.neighborroute.batchservice.repository.BatchLockKey;
import com.neighborroute.batchservice.repository.BatchMembershipLockInfo;
import com.neighborroute.batchservice.repository.BatchOrderRepository;
import com.neighborroute.batchservice.repository.BatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class BatchService {

    private final OrderClient orderClient;

    private final BatchRepository batchRepository;

    private final BatchOrderRepository batchOrderRepository;

    private final BatchCreationLock batchCreationLock;

    private final OpenBatchCacheInvalidator cacheInvalidator;

    private final JdbcTemplate jdbcTemplate;


    public BatchService(
            OrderClient orderClient,
            BatchRepository batchRepository,
            BatchOrderRepository batchOrderRepository,
            BatchCreationLock batchCreationLock,
            OpenBatchCacheInvalidator cacheInvalidator,
            JdbcTemplate jdbcTemplate
    ) {
        this.orderClient = orderClient;
        this.batchRepository = batchRepository;
        this.batchOrderRepository = batchOrderRepository;
        this.batchCreationLock = batchCreationLock;
        this.cacheInvalidator=cacheInvalidator;
        this.jdbcTemplate=jdbcTemplate;
    }


    @Transactional
    public JoinBatchResult joinBatch(UUID orderId) {
        batchCreationLock.lockOrder(orderId);

        OrderDetails initialOrder = orderClient.getOrder(orderId);

        batchCreationLock.lock(
                initialOrder.storeId(),
                initialOrder.deliveryBuilding()
        );

        OrderDetails order = orderClient.getOrder(orderId);

        if (!Objects.equals(initialOrder.storeId(), order.storeId())
                || !Objects.equals(
                initialOrder.deliveryBuilding(),
                order.deliveryBuilding()
        )) {
            throw new IllegalStateException("Order grouping attributes changed concurrently");
        }

        if (!"CONFIRMED".equals(order.status())) {
            throw new OrderNotConfirmedException(orderId);
        }

        Optional<BatchOrder> existingOrder = batchOrderRepository
                .findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.ACTIVE
                );

        if (existingOrder.isPresent()) {
            UUID batchId = existingOrder.get()
                    .getBatch()
                    .getId();

            return new JoinBatchResult(
                    batchId,
                    JoinBatchOutcome.ALREADY_JOINED
            );
        }

        Instant now = Instant.now();

        Optional<Batch> existingBatch = batchRepository
                .findFirstByStoreIdAndDeliveryBuildingAndStatusAndExpiresAtAfterOrderByCreatedAtAsc(
                        order.storeId(),
                        order.deliveryBuilding(),
                        BatchStatus.OPEN,
                        now
                );

        boolean created = existingBatch.isEmpty();

        Batch batch;

        if (existingBatch.isPresent()) {
            batch = existingBatch.get();

            lockAndCheckOpen(batch.getId());

            if (!batch.getExpiresAt().isAfter(Instant.now())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Batch has expired"
                );
            }
        } else {
            batch = batchRepository.save(
                    new Batch(
                            order.storeId(),
                            order.deliveryBuilding(),
                            now,
                            Duration.ofMinutes(10)
                    )
            );
        }

        BatchOrder batchOrder = new BatchOrder(
                batch,
                orderId,
                now
        );

        batchOrderRepository.save(batchOrder);

        cacheInvalidator.evictAfterCommit(
                batch.getStoreId(),
                batch.getDeliveryBuilding()
        );

        return new JoinBatchResult(
                batch.getId(),
                created
                        ? JoinBatchOutcome.CREATED
                        : JoinBatchOutcome.JOINED
        );
    }

    @Transactional
    public void leaveBatch(UUID orderId) {
        batchCreationLock.lockOrder(orderId);

        Optional<BatchMembershipLockInfo> optionalInfo =
                batchOrderRepository.findMembershipLockInfo(
                        orderId,
                        BatchOrderStatus.ACTIVE
                );


        if (optionalInfo.isEmpty()) {
            return;
        }

        BatchMembershipLockInfo info = optionalInfo.get();

        batchCreationLock.lock(
                info.storeId(),
                info.deliveryBuilding()
        );

        Optional<BatchOrder> optionalOrder =
                batchOrderRepository.findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.ACTIVE
                );

        if (optionalOrder.isEmpty()) {
            return;
        }

        BatchOrder batchOrder = optionalOrder.get();
        Batch batch = batchOrder.getBatch();

        if (!batch.getId().equals(info.batchId())) {
            throw new BatchMembershipChangedException(
                    "Order membership changed concurrently"
            );
        }

        lockAndCheckOpen(batch.getId());

        batchOrder.remove(Instant.now());

        cacheInvalidator.evictAfterCommit(
                batch.getStoreId(),
                batch.getDeliveryBuilding()
        );
    }

    private void lockAndCheckOpen(UUID batchId) {
        String status = jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM batches
                WHERE id = ?
                FOR UPDATE
                """,
                String.class,
                batchId
        );

        if (!"OPEN".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Batch is not OPEN"
            );
        }
    }



}