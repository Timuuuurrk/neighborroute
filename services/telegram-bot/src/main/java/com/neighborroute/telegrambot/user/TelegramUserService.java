package com.neighborroute.telegrambot.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TelegramUserService {

    private final JdbcTemplate jdbcTemplate;

    public TelegramUserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID registerIfAbsent(long telegramUserId) {

        UUID newCustomerId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO bot_users (
                    telegram_user_id,
                    customer_id
                )
                VALUES (?, ?)
                ON CONFLICT (telegram_user_id)
                DO NOTHING
                """,
                telegramUserId,
                newCustomerId
        );

        return jdbcTemplate.queryForObject(
                """
                SELECT customer_id
                FROM bot_users
                WHERE telegram_user_id = ?
                """,
                UUID.class,
                telegramUserId
        );
    }


    public record Address(String building, String details) {
    }

    public String getDialogState(long telegramUserId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT dialog_state
                FROM bot_users
                WHERE telegram_user_id = ?
                """,
                String.class,
                telegramUserId
        );
    }

    public Address getAddress(long telegramUserId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT delivery_building, delivery_details
                FROM bot_users
                WHERE telegram_user_id = ?
                """,
                (rs, rowNum) -> new Address(
                        rs.getString("delivery_building"),
                        rs.getString("delivery_details")
                ),
                telegramUserId
        );
    }

    public void beginAddressInput(long telegramUserId) {
        jdbcTemplate.update(
                """
                UPDATE bot_users
                SET dialog_state = 'AWAITING_BUILDING',
                    draft_building = NULL
                WHERE telegram_user_id = ?
                AND dialog_state = 'IDLE'
                """,
                telegramUserId
        );
    }

    public void saveDraftBuilding(
            long telegramUserId,
            String building
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE bot_users
                SET draft_building = ?,
                    dialog_state = 'AWAITING_DETAILS'
                WHERE telegram_user_id = ?
                  AND dialog_state = 'AWAITING_BUILDING'
                """,
                building,
                telegramUserId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "User is not awaiting building"
            );
        }
    }

    public Address completeAddressInput(
            long telegramUserId,
            String details
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE bot_users
                SET delivery_building = draft_building,
                    delivery_details = ?,
                    draft_building = NULL,
                    dialog_state = 'IDLE'
                WHERE telegram_user_id = ?
                  AND dialog_state = 'AWAITING_DETAILS'
                  AND draft_building IS NOT NULL
                """,
                details,
                telegramUserId
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "User is not awaiting delivery details"
            );
        }

        return getAddress(telegramUserId);
    }


    public void cancelAddressInput(long telegramUserId) {
        jdbcTemplate.update("""
            UPDATE bot_users
            SET dialog_state = 'IDLE',
                draft_building = NULL
            WHERE telegram_user_id = ?
              AND dialog_state IN (
                  'AWAITING_BUILDING',
                  'AWAITING_DETAILS'
              )
            """, telegramUserId);
    }


}
