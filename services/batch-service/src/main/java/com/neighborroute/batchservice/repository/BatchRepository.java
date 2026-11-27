
package com.neighborroute.batchservice.repository;

import com.neighborroute.batchservice.domain.Batch;
import com.neighborroute.batchservice.domain.BatchOrderStatus;
import com.neighborroute.batchservice.domain.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BatchRepository extends JpaRepository<Batch, UUID> {

    /*
    SELECT * FROM batches
    WHERE store_id = :storeId
    AND delivery_building = :deliveryBuilding
    AND status = :status AND expires_at > :now
    ORDER BY created_at ASC
    LIMIT 1;
     */
    Optional<Batch>
    findFirstByStoreIdAndDeliveryBuildingAndStatusAndExpiresAtAfterOrderByCreatedAtAsc(
            Long storeId,
            String deliveryBuilding,
            BatchStatus status,
            Instant now
    );

    List<Batch> findByStatusAndExpiresAtLessThanEqual(
            BatchStatus status,
            Instant now
    );



    @Query("""
    SELECT b.id
    FROM Batch b
    WHERE b.status = :status
      AND b.expiresAt <= :now
    """)
    List<UUID> findExpiredBatchIds(
            @Param("status") BatchStatus status,
            @Param("now") Instant now
    );


    @Query("""
    SELECT new com.neighborroute.batchservice.repository.BatchLockKey(
        b.storeId,
        b.deliveryBuilding
    )
    FROM Batch b
    WHERE b.id = :id
    """)
    Optional<BatchLockKey> findLockKeyById(
            @Param("id") UUID id
    );

}
