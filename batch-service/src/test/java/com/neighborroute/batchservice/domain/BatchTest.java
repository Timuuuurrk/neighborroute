
package com.neighborroute.batchservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BatchTest {

    @Test
    void shouldRejectOrdersAfterExpiration() {
        Instant createdAt =
                Instant.parse("2026-11-19T12:00:00Z");

        Batch batch = new Batch(
                1L,
                "Lenina 10",
                createdAt,
                Duration.ofMinutes(10)
        );

        assertTrue(batch.canAcceptOrders(
                createdAt.plus(Duration.ofMinutes(9))
        ));

        assertFalse(batch.canAcceptOrders(
                createdAt.plus(Duration.ofMinutes(10))
        ));

        assertFalse(batch.canAcceptOrders(
                createdAt.plus(Duration.ofMinutes(11))
        ));
    }


    @Test
    void shouldFollowBatchLifecycle() {
        Batch batch = new Batch(
                1L,
                "Lenina 10",
                Instant.parse("2026-11-19T12:00:00Z"),
                Duration.ofMinutes(10)
        );

        assertEquals(BatchStatus.OPEN, batch.getStatus());

        batch.close();

        assertEquals(BatchStatus.CLOSED, batch.getStatus());

        batch.markDelivered();

        assertEquals(BatchStatus.DELIVERED, batch.getStatus());
    }


    @Test
    void shouldNotDeliverOpenBatch() {
        Batch batch = new Batch(
                1L,
                "Lenina 10",
                Instant.parse("2026-11-19T12:00:00Z"),
                Duration.ofMinutes(10)
        );

        assertThrows(
                IllegalStateException.class,
                batch::markDelivered
        );

        assertEquals(BatchStatus.OPEN, batch.getStatus());
    }


}
