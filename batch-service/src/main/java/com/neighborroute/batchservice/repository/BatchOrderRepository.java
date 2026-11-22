
package com.neighborroute.batchservice.repository;

import com.neighborroute.batchservice.domain.BatchOrder;
import com.neighborroute.batchservice.domain.BatchOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BatchOrderRepository
        extends JpaRepository<BatchOrder, UUID> {

    Optional<BatchOrder> findByOrderIdAndStatus(
            UUID orderId,
            BatchOrderStatus status
    );

    List<BatchOrder> findByBatch_IdAndStatus(
            UUID batchId,
            BatchOrderStatus status
    );

    long countByBatch_IdAndStatus(
            UUID batchId,
            BatchOrderStatus status
    );

    boolean existsByOrderIdAndStatus(
            UUID orderId,
            BatchOrderStatus status
    );
}
