
package com.neighborroute.telegrambot.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class OrderDraftService {

    private final JdbcTemplate jdbcTemplate;

    public OrderDraftService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Draft(
            UUID customerId,
            String building,
            String details,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            UUID createdOrderId
    ) {
    }

    public void begin(long userId) {
        int updated = jdbcTemplate.update("""
                UPDATE bot_users
                SET dialog_state = 'AWAITING_PRODUCT',
                    draft_building = NULL,
                    draft_product_name = NULL,
                    draft_quantity = NULL,
                    draft_unit_price = NULL,
                    created_order_id = NULL
                WHERE telegram_user_id = ?
                        AND delivery_building IS NOT NULL
                        AND dialog_state = 'IDLE'
                        AND created_order_id IS NULL
                """, userId);

        if (updated != 1) {
            throw new IllegalStateException(
                    "User has no saved delivery address"
            );
        }
    }

    public void saveProduct(long userId, String product) {
        int updated = jdbcTemplate.update("""
                UPDATE bot_users
                SET draft_product_name = ?,
                    dialog_state = 'AWAITING_QUANTITY'
                WHERE telegram_user_id = ?
                  AND dialog_state = 'AWAITING_PRODUCT'
                """, product, userId);

        requireUpdated(updated);
    }

    public void saveQuantity(long userId, int quantity) {
        int updated = jdbcTemplate.update("""
                UPDATE bot_users
                SET draft_quantity = ?,
                    dialog_state = 'AWAITING_PRICE'
                WHERE telegram_user_id = ?
                  AND dialog_state = 'AWAITING_QUANTITY'
                """, quantity, userId);

        requireUpdated(updated);
    }

    public void savePrice(long userId, BigDecimal price) {
        int updated = jdbcTemplate.update("""
                UPDATE bot_users
                SET draft_unit_price = ?,
                    dialog_state = 'AWAITING_CONFIRMATION'
                WHERE telegram_user_id = ?
                  AND dialog_state = 'AWAITING_PRICE'
                """, price, userId);

        requireUpdated(updated);
    }

    public Draft getDraft(long userId) {
        return jdbcTemplate.queryForObject("""
                SELECT customer_id,
                       delivery_building,
                       delivery_details,
                       draft_product_name,
                       draft_quantity,
                       draft_unit_price,
                       created_order_id
                FROM bot_users
                WHERE telegram_user_id = ?
                """,
                (rs, rowNum) -> new Draft(
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("delivery_building"),
                        rs.getString("delivery_details"),
                        rs.getString("draft_product_name"),
                        rs.getInt("draft_quantity"),
                        rs.getBigDecimal("draft_unit_price"),
                        rs.getObject("created_order_id", UUID.class)
                ),
                userId
        );
    }

    public boolean claimCreation(long userId) {
        return jdbcTemplate.update("""
                UPDATE bot_users
                SET dialog_state = 'CREATING_ORDER'
                WHERE telegram_user_id = ?
                  AND dialog_state = 'AWAITING_CONFIRMATION'
                """, userId) == 1;
    }

    public void rememberCreatedOrder(
            long userId,
            UUID orderId
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE bot_users
                SET created_order_id = ?,
                    dialog_state = 'CONFIRMING_ORDER'
                WHERE telegram_user_id = ?
                  AND dialog_state = 'CREATING_ORDER'
                """, orderId, userId);

        requireUpdated(updated);
    }

    public void complete(long userId) {
        int updated = jdbcTemplate.update("""
                UPDATE bot_users
                SET dialog_state = 'IDLE',
                    draft_product_name = NULL,
                    draft_quantity = NULL,
                    draft_unit_price = NULL,
                    created_order_id = NULL
                WHERE telegram_user_id = ?
                  AND dialog_state = 'CONFIRMING_ORDER'
                """, userId);

        requireUpdated(updated);
    }

    public void cancelDraft(long userId) {
        jdbcTemplate.update("""
                UPDATE bot_users
                SET dialog_state = 'IDLE',
                    draft_product_name = NULL,
                    draft_quantity = NULL,
                    draft_unit_price = NULL,
                    created_order_id = NULL
                WHERE telegram_user_id = ?
                  AND dialog_state IN (
                    'AWAITING_PRODUCT',
                    'AWAITING_QUANTITY',
                    'AWAITING_PRICE',
                    'AWAITING_CONFIRMATION'
                  )
                """, userId);
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Unexpected order draft state"
            );
        }
    }


    @Transactional
    public void prepareBatchJoin(long userId, UUID orderId) {
        int updated = jdbcTemplate.update("""
            UPDATE bot_users
            SET dialog_state = 'JOIN_PENDING'
            WHERE telegram_user_id = ?
              AND dialog_state = 'CONFIRMING_ORDER'
              AND created_order_id = ?
            """, userId, orderId);

        requireUpdated(updated);

        int inserted = jdbcTemplate.update("""
            INSERT INTO bot_orders (
                order_id, telegram_user_id
            )
            VALUES (?, ?)
            ON CONFLICT (order_id) DO NOTHING
            """, orderId, userId);

        requireUpdated(inserted);
    }

    public boolean claimBatchJoin(long userId, UUID orderId) {
        return jdbcTemplate.update("""
            UPDATE bot_users
            SET dialog_state = 'JOINING_BATCH'
            WHERE telegram_user_id = ?
              AND created_order_id = ?
              AND dialog_state = 'JOIN_PENDING'
            """, userId, orderId) == 1;
    }

    @Transactional
    public void completeBatchJoin(
            long userId,
            UUID orderId,
            UUID batchId
    ) {
        int updatedOrder = jdbcTemplate.update("""
            UPDATE bot_orders
            SET batch_id = ?,
                join_status = 'JOINED'
            WHERE order_id = ?
              AND telegram_user_id = ?
              AND join_status = 'PENDING'
            """, batchId, orderId, userId);

        requireUpdated(updatedOrder);

        int updatedUser = jdbcTemplate.update("""
            UPDATE bot_users
            SET dialog_state = 'IDLE',
                draft_product_name = NULL,
                draft_quantity = NULL,
                draft_unit_price = NULL,
                created_order_id = NULL
            WHERE telegram_user_id = ?
              AND created_order_id = ?
              AND dialog_state = 'JOINING_BATCH'
            """, userId, orderId);

        requireUpdated(updatedUser);
    }

    @Transactional
    public void markBatchJoinUncertain(
            long userId,
            UUID orderId
    ) {
        int updatedOrder = jdbcTemplate.update("""
            UPDATE bot_orders
            SET join_status = 'REVIEW_REQUIRED'
            WHERE order_id = ?
              AND telegram_user_id = ?
              AND join_status = 'PENDING'
            """, orderId, userId);

        requireUpdated(updatedOrder);

        int updatedUser = jdbcTemplate.update("""
            UPDATE bot_users
            SET dialog_state = 'JOIN_REVIEW_REQUIRED'
            WHERE telegram_user_id = ?
              AND created_order_id = ?
              AND dialog_state = 'JOINING_BATCH'
            """, userId, orderId);

        requireUpdated(updatedUser);
    }

}
