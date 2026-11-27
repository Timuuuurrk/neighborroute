package com.neighborroute.orders.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Version;

@Entity
@Table(name="orders")
public class Order {

    @Id
    @GeneratedValue(strategy=GenerationType.UUID)
    private UUID id;

    @Column(name="customer_id", nullable=false)
    private UUID customerId;

    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    @JoinColumn(name="store_id", nullable=false)
    private Store store;

    @Column(name="delivery_building", nullable=false, length=250)
    private String deliveryBuilding;

    @Column(name="delivery_details", length=250)
    private String deliveryDetails;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=30)
    private OrderStatus status;

    @Column(name="created_at", nullable=false)
    private Instant createdAt;

    @Version
    @Column(name="version", nullable = false)
    private Long version;

    protected Order() {}

    public Order(UUID customerId, Store store, String deliveryBuilding, String deliveryDetails) {
        this.customerId=customerId;
        this.store=store;
        this.deliveryBuilding=deliveryBuilding;
        this.deliveryDetails=deliveryDetails;
        this.status=OrderStatus.CREATED;
    }

    @PrePersist
    protected void onCreate() {
        if(createdAt==null){
            createdAt=Instant.now();
        }
    }

    public UUID getId(){
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public Store getStore() {
        return store;
    }

    public String getDeliveryBuilding() {
        return deliveryBuilding;
    }

    public String getDeliveryDetails() {
        return deliveryDetails;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {return version;}

    public void confirm() {
        changeStatus(OrderStatus.CONFIRMED);
    }

    public void cancel() {
        changeStatus(OrderStatus.CANCELLED);
    }

    public void deliver() {
        if (status == OrderStatus.DELIVERED) {
            return;
        }

        if (status != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException(
                    "Нельзя доставить заказ со статусом " + status
            );
        }

        status = OrderStatus.DELIVERED;
    }

    private void changeStatus(OrderStatus newStatus) {
        if (status!=OrderStatus.CREATED) {
            throw new InvalidOrderStateException(
                    "Нельзя изменить тип заказа с " +status + " на " + newStatus
            );
        }
        this.status=newStatus;
    }

    public void beginCancellation() {
        if (status != OrderStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Только подтвержденные заказы можно отменить"
            );
        }

        status = OrderStatus.CANCEL_PENDING;
    }

    public void completeCancellation() {
        if (status != OrderStatus.CANCEL_PENDING) {
            throw new IllegalStateException(
                    "Заказ не в процессе отмены"
            );
        }

        status = OrderStatus.CANCELLED;
    }

    public void rejectCancellation() {
        if (status != OrderStatus.CANCEL_PENDING) {
            throw new IllegalStateException(
                    "Заказ не в процессе отмены"
            );
        }

        status = OrderStatus.CONFIRMED;
    }
}
