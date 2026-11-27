
package com.neighborroute.batchservice.repository;

import com.neighborroute.batchservice.domain.BatchOrder;
import com.neighborroute.batchservice.domain.BatchOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query("""
    SELECT new com.neighborroute.batchservice.repository.BatchLockKey(
        b.storeId,
        b.deliveryBuilding
    )
    FROM BatchOrder bo
    JOIN bo.batch b
    WHERE bo.orderId = :orderId
      AND bo.status = :status
    """)
    Optional<BatchLockKey> findLockKeyByOrderIdAndStatus(
            @Param("orderId") UUID orderId,
            @Param("status") BatchOrderStatus status
    );

    @Query("""
    SELECT new com.neighborroute.batchservice.repository.BatchMembershipLockInfo(
        b.id,
        b.storeId,
        b.deliveryBuilding
    )
    FROM BatchOrder bo
    JOIN bo.batch b
    WHERE bo.orderId = :orderId
      AND bo.status = :status
    """)
    Optional<BatchMembershipLockInfo> findMembershipLockInfo(
            @Param("orderId") UUID orderId,
            @Param("status") BatchOrderStatus status
    );

}
