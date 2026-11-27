package com.neighborroute.batchservice.service;

import com.neighborroute.batchservice.dto.OpenBatchView;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OpenBatchQueryService {

    private final JdbcTemplate jdbcTemplate;

    public OpenBatchQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Cacheable(
            cacheNames = "open-batches",
            key = "#p0 + ':' + #p1"
    )
    public List<OpenBatchView> findOpenBatches(
            Long storeId,
            String deliveryBuilding
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    b.id,
                    b.store_id,
                    b.delivery_building,
                    b.expires_at,
                    COUNT(bo.order_id) AS active_orders
                FROM batches b
                LEFT JOIN batch_orders bo
                    ON bo.batch_id = b.id
                    AND bo.status = 'ACTIVE'
                WHERE b.status = 'OPEN'
                    AND b.store_id = ?
                    AND b.delivery_building = ?
                GROUP BY
                    b.id,
                    b.store_id,
                    b.delivery_building,
                    b.expires_at
                ORDER BY b.expires_at, b.id
                """,
                (rs, rowNum) -> new OpenBatchView(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getLong("store_id"),
                        rs.getString("delivery_building"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getLong("active_orders")
                ),
                storeId,
                deliveryBuilding
        );
    }
}