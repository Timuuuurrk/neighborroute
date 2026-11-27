
package com.neighborroute.batchservice.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "batch_orders")
public class BatchOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchOrderStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected BatchOrder() {
        // Для Hibernate
    }

    public BatchOrder(Batch batch, UUID orderId, Instant joinedAt) {
        this.batch = Objects.requireNonNull(batch);
        this.orderId = Objects.requireNonNull(orderId);
        this.joinedAt = Objects.requireNonNull(joinedAt);

        this.status = BatchOrderStatus.ACTIVE;
        this.removedAt = null;
    }

    public boolean isActive() {
        return status == BatchOrderStatus.ACTIVE;
    }

    public void remove(Instant now) {
        Objects.requireNonNull(now);

        if (!isActive()) {
            throw new IllegalStateException(
                    "Заказ уже удалён из сессии"
            );
        }

        if (now.isBefore(joinedAt)) {
            throw new IllegalArgumentException(
                    "Удаление не может предшествовать добавлению в сессию"
            );
        }

        this.status = BatchOrderStatus.REMOVED;
        this.removedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Batch getBatch() {
        return batch;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BatchOrderStatus getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
