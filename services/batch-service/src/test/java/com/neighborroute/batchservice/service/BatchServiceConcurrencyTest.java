
package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.client.OrderClient;
import com.neighborroute.batchservice.client.OrderDetails;
import com.neighborroute.batchservice.domain.Batch;
import com.neighborroute.batchservice.domain.BatchOrder;
import com.neighborroute.batchservice.domain.BatchOrderStatus;
import com.neighborroute.batchservice.domain.BatchStatus;
import com.neighborroute.batchservice.exception.BatchNotOpenException;
import com.neighborroute.batchservice.exception.OrderNotConfirmedException;
import com.neighborroute.batchservice.repository.BatchOrderRepository;
import com.neighborroute.batchservice.repository.BatchRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "batch.expiration.scheduler.enabled=false"
})
@Testcontainers
class BatchServiceConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private BatchService batchService;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private BatchOrderRepository batchOrderRepository;

    @MockitoBean
    private OrderClient orderClient;

    @Autowired
    private BatchExpirationService expirationService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private BatchExpirationProcessor expirationProcessor;

    @Autowired
    private BatchCreationLock batchCreationLock;

    @BeforeEach
    void cleanDatabase() {
        batchOrderRepository.deleteAll();
        batchRepository.deleteAll();
    }

    @Test
    void concurrentOrdersShouldJoinSameBatch() throws Exception {
        UUID orderId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();

        when(orderClient.getOrder(orderId1))
                .thenReturn(new OrderDetails(
                        orderId1, 1L, "Lenina 10", "CONFIRMED"
                ));

        when(orderClient.getOrder(orderId2))
                .thenReturn(new OrderDetails(
                        orderId2, 1L, "Lenina 10", "CONFIRMED"
                ));

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Void> first = executor.submit(() -> {
            ready.countDown();
            start.await();
            batchService.joinBatch(orderId1);
            return null;
        });

        Future<Void> second = executor.submit(() -> {
            ready.countDown();
            start.await();
            batchService.joinBatch(orderId2);
            return null;
        });

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));

            start.countDown();

            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);

            assertEquals(1, batchRepository.count());

            Batch batch = batchRepository.findAll().get(0);


            assertEquals(
                    2,
                    batchOrderRepository.countByBatch_IdAndStatus(
                            batch.getId(),
                            BatchOrderStatus.ACTIVE
                    )
            );

            assertTrue(
                    batchOrderRepository.existsByOrderIdAndStatus(
                            orderId1, BatchOrderStatus.ACTIVE
                    )
            );

            assertTrue(
                    batchOrderRepository.existsByOrderIdAndStatus(
                            orderId2, BatchOrderStatus.ACTIVE
                    )
            );

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void ordersFromDifferentBuildingsShouldCreateSeparateBatches() {
        UUID orderId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();

        when(orderClient.getOrder(orderId1))
                .thenReturn(new OrderDetails(
                        orderId1, 1L, "Lenina 10", "CONFIRMED"
                ));

        when(orderClient.getOrder(orderId2))
                .thenReturn(new OrderDetails(
                        orderId2, 1L, "Lenina 20", "CONFIRMED"
                ));

        batchService.joinBatch(orderId1);
        batchService.joinBatch(orderId2);

        assertEquals(2, batchRepository.count());

        for (Batch batch : batchRepository.findAll()) {
            assertEquals(
                    1,
                    batchOrderRepository.countByBatch_IdAndStatus(
                            batch.getId(),
                            BatchOrderStatus.ACTIVE
                    )
            );
        }
    }


    @Test
    void ordersFromDifferentStoresShouldCreateSeparateBatches() {
        UUID orderId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();

        when(orderClient.getOrder(orderId1))
                .thenReturn(new OrderDetails(
                        orderId1, 1L, "Lenina 10", "CONFIRMED"
                ));

        when(orderClient.getOrder(orderId2))
                .thenReturn(new OrderDetails(
                        orderId2, 2L, "Lenina 10", "CONFIRMED"
                ));

        batchService.joinBatch(orderId1);
        batchService.joinBatch(orderId2);

        assertEquals(2, batchRepository.count());
    }


    @Test
    void repeatedJoinShouldBeIdempotent() {
        UUID orderId = UUID.randomUUID();

        when(orderClient.getOrder(orderId))
                .thenReturn(new OrderDetails(
                        orderId,
                        1L,
                        "Main Street 10",
                        "CONFIRMED"
                ));

        JoinBatchResult first = batchService.joinBatch(orderId);
        JoinBatchResult second = batchService.joinBatch(orderId);

        assertEquals(JoinBatchOutcome.CREATED, first.outcome());
        assertEquals(JoinBatchOutcome.ALREADY_JOINED, second.outcome());

        assertEquals(first.batchId(), second.batchId());

        assertEquals(1, batchRepository.count());

        assertEquals(
                1,
                batchOrderRepository.countByBatch_IdAndStatus(
                        first.batchId(),
                        BatchOrderStatus.ACTIVE
                )
        );
    }


    @Test
    void shouldCloseExpiredBatchesOnly() {
        Instant now = Instant.now();

        Batch expired = batchRepository.saveAndFlush(
                new Batch(
                        1L,
                        "Expired Street 10",
                        now.minus(Duration.ofMinutes(11)),
                        Duration.ofMinutes(10)
                )
        );

        Batch active = batchRepository.saveAndFlush(
                new Batch(
                        1L,
                        "Active Street 20",
                        now,
                        Duration.ofMinutes(10)
                )
        );

        expirationService.closeExpiredBatches();

        Batch expiredAfter = batchRepository
                .findById(expired.getId())
                .orElseThrow();

        Batch activeAfter = batchRepository
                .findById(active.getId())
                .orElseThrow();

        assertEquals(BatchStatus.EXPIRED, expiredAfter.getStatus());
        assertEquals(BatchStatus.OPEN, activeAfter.getStatus());
    }


    @Test
    void shouldNotCloseAlreadyClosedBatchAgain() {
        Instant now = Instant.now();

        Batch batch = batchRepository.saveAndFlush(
                new Batch(
                        1L,
                        "Expired Street 30",
                        now.minus(Duration.ofMinutes(11)),
                        Duration.ofMinutes(10)
                )
        );

        int firstResult = expirationService.closeExpiredBatches();
        int secondResult = expirationService.closeExpiredBatches();

        Batch updated = batchRepository.findById(batch.getId())
                .orElseThrow();

        assertEquals(BatchStatus.EXPIRED, updated.getStatus());
        assertEquals(0, secondResult);
    }



    @Test
    void shouldCloseAndExpireBatchesCorrectly() {
        Instant now = Instant.now();
        Instant createdAt = now.minus(Duration.ofMinutes(11));

        Batch withActive = batchRepository.saveAndFlush(
                new Batch(
                        1L,
                        "Test Street 101",
                        createdAt,
                        Duration.ofMinutes(10)
                )
        );

        Batch withRemoved = batchRepository.saveAndFlush(
                new Batch(
                        1L,
                        "Test Street 102",
                        createdAt,
                        Duration.ofMinutes(10)
                )
        );

        Batch empty = batchRepository.saveAndFlush(
                new Batch(
                        1L,
                        "Test Street 103",
                        createdAt,
                        Duration.ofMinutes(10)
                )
        );

        batchOrderRepository.saveAndFlush(
                new BatchOrder(withActive, UUID.randomUUID(), createdAt)
        );

        BatchOrder removedOrder = new BatchOrder(
                withRemoved,
                UUID.randomUUID(),
                createdAt
        );
        removedOrder.remove(now);
        batchOrderRepository.saveAndFlush(removedOrder);

        int first = expirationService.closeExpiredBatches();
        int second = expirationService.closeExpiredBatches();

        assertEquals(3, first);
        assertEquals(0, second);

        assertEquals(
                BatchStatus.CLOSED,
                batchRepository.findById(withActive.getId())
                        .orElseThrow().getStatus()
        );

        assertEquals(
                BatchStatus.EXPIRED,
                batchRepository.findById(withRemoved.getId())
                        .orElseThrow().getStatus()
        );

        assertEquals(
                BatchStatus.EXPIRED,
                batchRepository.findById(empty.getId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldWaitForJoinBeforeClosingBatch() throws Exception {
        Long storeId = 1L;
        String building = "Concurrency " + UUID.randomUUID();

        Instant createdAt = Instant.now().minus(Duration.ofMinutes(11));

        Batch batch = batchRepository.saveAndFlush(
                new Batch(
                        storeId,
                        building,
                        createdAt,
                        Duration.ofMinutes(10)
                )
        );

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    batchCreationLock.lock(storeId, building);

                    batchOrderRepository.saveAndFlush(
                            new BatchOrder(
                                    batch,
                                    UUID.randomUUID(),
                                    Instant.now()
                            )
                    );

                    lockAcquired.countDown();

                    try {
                        if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Commit timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

            Future<Integer> second = executor.submit(() -> {
                secondStarted.countDown();
                return expirationProcessor.closeOneBatch(batch.getId());
            });

            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

            assertThrows(
                    TimeoutException.class,
                    () -> second.get(300, TimeUnit.MILLISECONDS)
            );

            allowCommit.countDown();

            first.get(5, TimeUnit.SECONDS);

            assertEquals(1, second.get(5, TimeUnit.SECONDS));

            Batch updated = batchRepository.findById(batch.getId())
                    .orElseThrow();

            assertEquals(BatchStatus.CLOSED, updated.getStatus());

            assertEquals(
                    1,
                    batchOrderRepository.countByBatch_IdAndStatus(
                            batch.getId(),
                            BatchOrderStatus.ACTIVE
                    )
            );
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void shouldCreateNewBatchWhenPreviousBatchExpired() throws Exception {
        Long storeId = 1L;
        String building = "Race Street " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Instant createdAt = Instant.now().minus(Duration.ofMinutes(11));

        Batch oldBatch = batchRepository.saveAndFlush(
                new Batch(
                        storeId,
                        building,
                        createdAt,
                        Duration.ofMinutes(10)
                )
        );

        when(orderClient.getOrder(orderId))
                .thenReturn(new OrderDetails(
                        orderId,
                        storeId,
                        building,
                        "CONFIRMED"
                ));

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> expiration = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    batchCreationLock.lock(storeId, building);

                    Batch batch = batchRepository
                            .findById(oldBatch.getId())
                            .orElseThrow();

                    batch.expire();

                    lockAcquired.countDown();

                    try {
                        if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

            Future<JoinBatchResult> joining = executor.submit(
                    () -> batchService.joinBatch(orderId)
            );

            allowCommit.countDown();

            expiration.get(5, TimeUnit.SECONDS);

            JoinBatchResult result = joining.get(5, TimeUnit.SECONDS);

            assertEquals(JoinBatchOutcome.CREATED, result.outcome());

            assertNotEquals(oldBatch.getId(), result.batchId());

            assertEquals(
                    BatchStatus.EXPIRED,
                    batchRepository.findById(oldBatch.getId())
                            .orElseThrow().getStatus()
            );

            assertEquals(
                    BatchStatus.OPEN,
                    batchRepository.findById(result.batchId())
                            .orElseThrow().getStatus()
            );

            assertEquals(
                    1,
                    batchOrderRepository.countByBatch_IdAndStatus(
                            result.batchId(),
                            BatchOrderStatus.ACTIVE
                    )
            );
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldLeaveOpenBatch() {
        Long storeId = 1L;
        String building = "Leave Street " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Batch batch = batchRepository.saveAndFlush(
                new Batch(
                        storeId,
                        building,
                        Instant.now(),
                        Duration.ofMinutes(10)
                )
        );

        batchOrderRepository.saveAndFlush(
                new BatchOrder(
                        batch,
                        orderId,
                        Instant.now()
                )
        );

        batchService.leaveBatch(orderId);

        assertTrue(
                batchOrderRepository.findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.ACTIVE
                ).isEmpty()
        );

        assertTrue(
                batchOrderRepository.findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.REMOVED
                ).isPresent()
        );

        Batch updatedBatch = batchRepository
                .findById(batch.getId())
                .orElseThrow();

        assertEquals(BatchStatus.OPEN, updatedBatch.getStatus());
    }



    @Test
    void shouldLeaveBatchIdempotently() {
        Long storeId = 1L;
        String building = "Idempotent Street " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Batch batch = batchRepository.saveAndFlush(
                new Batch(
                        storeId,
                        building,
                        Instant.now(),
                        Duration.ofMinutes(10)
                )
        );

        batchOrderRepository.saveAndFlush(
                new BatchOrder(
                        batch,
                        orderId,
                        Instant.now()
                )
        );

        batchService.leaveBatch(orderId);

        assertDoesNotThrow(
                () -> batchService.leaveBatch(orderId)
        );

        assertTrue(
                batchOrderRepository.findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.ACTIVE
                ).isEmpty()
        );

        assertTrue(
                batchOrderRepository.findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.REMOVED
                ).isPresent()
        );

        assertEquals(
                BatchStatus.OPEN,
                batchRepository.findById(batch.getId())
                        .orElseThrow().getStatus()
        );
    }

    @Test
    void shouldNotLeaveClosedBatch() {
        Long storeId = 1L;
        String building = "Closed Street " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Batch batch = new Batch(
                storeId,
                building,
                Instant.now(),
                Duration.ofMinutes(10)
        );

        batch.close();
        batch = batchRepository.saveAndFlush(batch);

        batchOrderRepository.saveAndFlush(
                new BatchOrder(
                        batch,
                        orderId,
                        Instant.now()
                )
        );

        assertThrows(
                BatchNotOpenException.class,
                () -> batchService.leaveBatch(orderId)
        );

        assertTrue(
                batchOrderRepository.findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.ACTIVE
                ).isPresent()
        );

        assertTrue(
                batchOrderRepository.findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.REMOVED
                ).isEmpty()
        );

        assertEquals(
                BatchStatus.CLOSED,
                batchRepository.findById(batch.getId())
                        .orElseThrow()
                        .getStatus()
        );
    }

    @Test
    void shouldIgnoreLeaveFromExpiredBatch() {
        Long storeId = 1L;
        String building = "Expired Street " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Batch batch = new Batch(
                storeId,
                building,
                Instant.now().minus(Duration.ofMinutes(11)),
                Duration.ofMinutes(10)
        );

        batch.expire();
        batch = batchRepository.saveAndFlush(batch);

        assertDoesNotThrow(
                () -> batchService.leaveBatch(orderId)
        );

        assertEquals(
                BatchStatus.EXPIRED,
                batchRepository.findById(batch.getId())
                        .orElseThrow()
                        .getStatus()
        );
    }


    @Test
    void shouldLeaveBatchConcurrently() throws Exception {
        Long storeId = 1L;
        String building = "Concurrent Leave " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Batch batch = batchRepository.saveAndFlush(
                new Batch(
                        storeId,
                        building,
                        Instant.now(),
                        Duration.ofMinutes(10)
                )
        );

        batchOrderRepository.saveAndFlush(
                new BatchOrder(batch, orderId, Instant.now())
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Callable<Void> task = () -> {
                start.await();
                batchService.leaveBatch(orderId);
                return null;
            };

            Future<Void> first = executor.submit(task);
            Future<Void> second = executor.submit(task);

            start.countDown();

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertEquals(
                    0,
                    batchOrderRepository.countByBatch_IdAndStatus(
                            batch.getId(),
                            BatchOrderStatus.ACTIVE
                    )
            );

            assertTrue(
                    batchOrderRepository.findByOrderIdAndStatus(
                            orderId,
                            BatchOrderStatus.REMOVED
                    ).isPresent()
            );

            assertEquals(
                    BatchStatus.OPEN,
                    batchRepository.findById(batch.getId())
                            .orElseThrow().getStatus()
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void shouldExpireBatchWhenLastOrderLeaves() {
        Long storeId = 1L;
        String building = "Last Order " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Instant createdAt =
                Instant.now().minus(Duration.ofMinutes(11));

        Batch batch = batchRepository.saveAndFlush(
                new Batch(
                        storeId,
                        building,
                        createdAt,
                        Duration.ofMinutes(10)
                )
        );

        batchOrderRepository.saveAndFlush(
                new BatchOrder(batch, orderId, Instant.now())
        );

        batchService.leaveBatch(orderId);

        assertEquals(
                0,
                batchOrderRepository.countByBatch_IdAndStatus(
                        batch.getId(),
                        BatchOrderStatus.ACTIVE
                )
        );

        int processed = expirationProcessor.closeOneBatch(
                batch.getId()
        );

        assertEquals(1, processed);

        Batch updated = batchRepository.findById(batch.getId())
                .orElseThrow();

        assertEquals(BatchStatus.EXPIRED, updated.getStatus());

        assertTrue(
                batchOrderRepository.findByOrderIdAndStatus(
                        orderId,
                        BatchOrderStatus.REMOVED
                ).isPresent()
        );
    }

    @Test
    void shouldExpireBatchAfterConcurrentLeave() throws Exception {
        Long storeId = 1L;
        String building = "Concurrent Expiration " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Batch batch = batchRepository.saveAndFlush(
                new Batch(
                        storeId,
                        building,
                        Instant.now().minus(Duration.ofMinutes(11)),
                        Duration.ofMinutes(10)
                )
        );

        batchOrderRepository.saveAndFlush(
                new BatchOrder(batch, orderId, Instant.now())
        );

        CountDownLatch leaveCompleted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> leaving = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    batchCreationLock.lock(storeId, building);

                    batchService.leaveBatch(orderId);

                    leaveCompleted.countDown();

                    try {
                        if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            assertTrue(leaveCompleted.await(5, TimeUnit.SECONDS));

            CountDownLatch expirationStarted = new CountDownLatch(1);

            Future<Integer> expiring = executor.submit(() -> {
                expirationStarted.countDown();
                return expirationProcessor.closeOneBatch(batch.getId());
            });

            assertTrue(expirationStarted.await(5, TimeUnit.SECONDS));

            assertThrows(
                    TimeoutException.class,
                    () -> expiring.get(300, TimeUnit.MILLISECONDS)
            );

            allowCommit.countDown();

            leaving.get(5, TimeUnit.SECONDS);

            assertEquals(1, expiring.get(5, TimeUnit.SECONDS));

            assertEquals(
                    0,
                    batchOrderRepository.countByBatch_IdAndStatus(
                            batch.getId(),
                            BatchOrderStatus.ACTIVE
                    )
            );

            assertEquals(
                    BatchStatus.EXPIRED,
                    batchRepository.findById(batch.getId())
                            .orElseThrow()
                            .getStatus()
            );

            assertTrue(
                    batchOrderRepository.findByOrderIdAndStatus(
                            orderId,
                            BatchOrderStatus.REMOVED
                    ).isPresent()
            );

        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void shouldRejectLeaveWhenBatchClosesFirst() throws Exception {
        Long storeId = 1L;
        String building = "Closing Race " + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Batch batch = batchRepository.saveAndFlush(
                new Batch(
                        storeId,
                        building,
                        Instant.now().minus(Duration.ofMinutes(11)),
                        Duration.ofMinutes(10)
                )
        );

        batchOrderRepository.saveAndFlush(
                new BatchOrder(batch, orderId, Instant.now())
        );

        CountDownLatch batchClosed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> closing = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    batchCreationLock.lock(storeId, building);

                    Batch current = batchRepository.findById(batch.getId())
                            .orElseThrow();

                    current.close();
                    batchRepository.flush();

                    batchClosed.countDown();

                    try {
                        if (!allowCommit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            assertTrue(batchClosed.await(5, TimeUnit.SECONDS));

            Future<?> leaving = executor.submit(
                    () -> batchService.leaveBatch(orderId)
            );

            assertThrows(
                    TimeoutException.class,
                    () -> leaving.get(300, TimeUnit.MILLISECONDS)
            );

            allowCommit.countDown();

            closing.get(5, TimeUnit.SECONDS);

            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> leaving.get(5, TimeUnit.SECONDS)
            );

            assertInstanceOf(
                    BatchNotOpenException.class,
                    exception.getCause()
            );

            assertEquals(
                    BatchStatus.CLOSED,
                    batchRepository.findById(batch.getId())
                            .orElseThrow()
                            .getStatus()
            );

            assertEquals(
                    1,
                    batchOrderRepository.countByBatch_IdAndStatus(
                            batch.getId(),
                            BatchOrderStatus.ACTIVE
                    )
            );

            assertTrue(
                    batchOrderRepository.findByOrderIdAndStatus(
                            orderId,
                            BatchOrderStatus.REMOVED
                    ).isEmpty()
            );

        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectJoinWhenCancellationStarts() {
        UUID orderId = UUID.randomUUID();

        when(orderClient.getOrder(orderId))
                .thenReturn(
                        new OrderDetails(
                                orderId, 1L, "Main Street 10", "CONFIRMED"
                        ),
                        new OrderDetails(
                                orderId, 1L, "Main Street 10", "CANCEL_PENDING"
                        )
                );

        assertThrows(
                OrderNotConfirmedException.class,
                () -> batchService.joinBatch(orderId)
        );

        verify(orderClient, times(2)).getOrder(orderId);
    }



}
