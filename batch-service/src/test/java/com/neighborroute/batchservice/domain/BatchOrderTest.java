
package com.neighborroute.batchservice.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BatchOrderTest {

    @Test
    void shouldRemoveOrderFromBatch() {
        Instant joinedAt =
                Instant.parse("2026-11-19T12:00:00Z");

        Batch batch = new Batch(
                1L,
                "Lenina 10",
                joinedAt,
                Duration.ofMinutes(10)
        );

        BatchOrder batchOrder = new BatchOrder(
                batch,
                UUID.randomUUID(),
                joinedAt
        );

        assertTrue(batchOrder.isActive());
        assertNull(batchOrder.getRemovedAt());

        Instant removedAt = joinedAt.plusSeconds(60);

        batchOrder.remove(removedAt);

        assertFalse(batchOrder.isActive());
        assertEquals(
                BatchOrderStatus.REMOVED,
                batchOrder.getStatus()
        );
        assertEquals(
                removedAt,
                batchOrder.getRemovedAt()
        );
    }


    @Test
    void shouldNotRemoveOrderTwice() {
        Instant joinedAt =
                Instant.parse("2026-11-19T12:00:00Z");

        Batch batch = new Batch(
                1L,
                "Lenina 10",
                joinedAt,
                Duration.ofMinutes(10)
        );

        BatchOrder batchOrder = new BatchOrder(
                batch,
                UUID.randomUUID(),
                joinedAt
        );

        Instant firstRemoval = joinedAt.plusSeconds(60);
        batchOrder.remove(firstRemoval);

        assertThrows(
                IllegalStateException.class,
                () -> batchOrder.remove(
                        joinedAt.plusSeconds(120)
                )
        );

        assertEquals(
                BatchOrderStatus.REMOVED,
                batchOrder.getStatus()
        );
        assertEquals(
                firstRemoval,
                batchOrder.getRemovedAt()
        );
    }

}
