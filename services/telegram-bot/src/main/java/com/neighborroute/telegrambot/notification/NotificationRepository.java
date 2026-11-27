
package com.neighborroute.telegrambot.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Notification(
            UUID orderId,
            UUID batchId,
            long telegramUserId
    ) {
    }

    public Optional<Notification> claimNext() {
        return jdbcTemplate.query("""
                WITH picked AS (
                    SELECT order_id, notification_type
                    FROM bot_notifications
                    WHERE status = 'PENDING'
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE bot_notifications n
                SET status = 'SENDING',
                    send_started_at = CURRENT_TIMESTAMP
                FROM picked p
                WHERE n.order_id = p.order_id
                  AND n.notification_type = p.notification_type
                RETURNING
                    n.order_id,
                    n.batch_id,
                    n.telegram_user_id
                """, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }

            return Optional.of(new Notification(
                    rs.getObject("order_id", UUID.class),
                    rs.getObject("batch_id", UUID.class),
                    rs.getLong("telegram_user_id")
            ));
        });
    }

    public void markSent(UUID orderId) {
        int updated = jdbcTemplate.update("""
                UPDATE bot_notifications
                SET status = 'SENT',
                    sent_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                  AND notification_type = 'BATCH_DELIVERED'
                  AND status = 'SENDING'
                """, orderId);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Failed to mark notification as SENT"
            );
        }
    }

    public void markReviewRequired(UUID orderId) {
        jdbcTemplate.update("""
                UPDATE bot_notifications
                SET status = 'REVIEW_REQUIRED'
                WHERE order_id = ?
                  AND notification_type = 'BATCH_DELIVERED'
                  AND status = 'SENDING'
                """, orderId);
    }


    public int markStaleSendingAsReviewRequired() {
        return jdbcTemplate.update("""
            UPDATE bot_notifications
            SET status = 'REVIEW_REQUIRED'
            WHERE status = 'SENDING'
              AND send_started_at <
                  CURRENT_TIMESTAMP - INTERVAL '15 minutes'
            """);
    }

}
