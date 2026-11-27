
package com.neighborroute.batchservice.domain;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "batches")
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "delivery_building", nullable = false,
            length = 250)
    private String deliveryBuilding;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected Batch() {
        // for Hibernate
    }

    public Batch(
            Long storeId,
            String deliveryBuilding,
            Instant createdAt,
            Duration collectionWindow
    ) {
        if (collectionWindow == null ||
                collectionWindow.isZero() ||
                collectionWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "Окно сбора должно быть положительным"
            );
        }

        this.storeId = java.util.Objects.requireNonNull(storeId);
        this.deliveryBuilding =
                java.util.Objects.requireNonNull(deliveryBuilding);
        this.createdAt =
                java.util.Objects.requireNonNull(createdAt);

        this.expiresAt = createdAt.plus(collectionWindow);
        this.status = BatchStatus.OPEN;
    }


    public boolean canAcceptOrders(Instant now) {
        Objects.requireNonNull(now);

        return status == BatchStatus.OPEN
                && now.isBefore(expiresAt);
    }

    public void close() {
        if (status != BatchStatus.OPEN) {
            throw new IllegalStateException(
                    "Только открытую сессию можно закрыть"
            );
        }

        status = BatchStatus.CLOSED;
    }

    public void expire() {
        if (status != BatchStatus.OPEN) {
            throw new IllegalStateException(
                    "Only OPEN batch can be expired"
            );
        }

        status = BatchStatus.EXPIRED;
    }

    public void markDelivered() {
        if (status != BatchStatus.CLOSED) {
            throw new IllegalStateException(
                    "Только закрытая сессия может быть доставлена"
            );
        }

        status = BatchStatus.DELIVERED;
    }


    public UUID getId() {
        return id;
    }

    public Long getStoreId() {
        return storeId;
    }

    public String getDeliveryBuilding() {
        return deliveryBuilding;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
