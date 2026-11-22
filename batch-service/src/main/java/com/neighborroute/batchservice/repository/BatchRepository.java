
package com.neighborroute.batchservice.repository;

import com.neighborroute.batchservice.domain.Batch;
import com.neighborroute.batchservice.domain.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
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

}
