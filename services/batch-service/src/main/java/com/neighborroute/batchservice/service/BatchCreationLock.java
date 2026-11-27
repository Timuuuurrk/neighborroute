
package com.neighborroute.batchservice.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BatchCreationLock {

    private final JdbcTemplate jdbcTemplate;

    public BatchCreationLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lock(Long storeId, String deliveryBuilding) {
        String key = storeId + ":" + deliveryBuilding;

        jdbcTemplate.execute(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)
                )
                """,
                (PreparedStatementCallback<Void>) ps -> {
                    ps.setString(1, key);
                    ps.execute();
                    return null;
                }
        );
    }

    public void lockOrder(UUID orderId) {
        jdbcTemplate.query(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)
                )
                """,
                ps -> ps.setString(1, "order:" + orderId),
                rs -> {}
        );
    }
}
