
package com.neighborroute.telegrambot.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class OrderHistoryService {

    private final JdbcTemplate jdbcTemplate;

    public OrderHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record OrderEntry(
            UUID orderId,
            UUID batchId,
            String joinStatus
    ) {
    }

    public List<OrderEntry> findRecentOrders(long telegramUserId) {
        return jdbcTemplate.query(
                """
                SELECT order_id, batch_id, join_status
                FROM bot_orders
                WHERE telegram_user_id = ?
                ORDER BY created_at DESC, order_id DESC
                LIMIT 10
                """,
                (rs, rowNum) -> new OrderEntry(
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getString("join_status")
                ),
                telegramUserId
        );
    }


    public boolean ownsOrder(long telegramUserId, UUID orderId) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM bot_orders
                    WHERE telegram_user_id = ?
                      AND order_id = ?
                )
                """,
                Boolean.class,
                telegramUserId,
                orderId
        );

        return Boolean.TRUE.equals(exists);
    }

}
