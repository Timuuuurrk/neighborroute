CREATE TABLE bot_orders
(
    order_id         UUID PRIMARY KEY,

    telegram_user_id BIGINT NOT NULL
        REFERENCES bot_users (telegram_user_id),

    batch_id         UUID,

    join_status      VARCHAR(30)
                            NOT NULL DEFAULT 'PENDING',

    created_at       TIMESTAMPTZ
                            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT bot_orders_join_status_check
        CHECK (join_status IN (
                               'PENDING',
                               'JOINED',
                               'REVIEW_REQUIRED'
            )),

    CONSTRAINT bot_orders_batch_check
        CHECK (
            join_status <> 'JOINED'
                OR batch_id IS NOT NULL
            )
);

CREATE INDEX idx_bot_orders_user
    ON bot_orders (telegram_user_id, created_at DESC);

ALTER TABLE bot_users
DROP
CONSTRAINT bot_users_dialog_state_check;

ALTER TABLE bot_users
    ADD CONSTRAINT bot_users_dialog_state_check
        CHECK (dialog_state IN (
                                'IDLE',
                                'AWAITING_BUILDING',
                                'AWAITING_DETAILS',
                                'AWAITING_PRODUCT',
                                'AWAITING_QUANTITY',
                                'AWAITING_PRICE',
                                'AWAITING_CONFIRMATION',
                                'CREATING_ORDER',
                                'CONFIRMING_ORDER',
                                'JOIN_PENDING',
                                'JOINING_BATCH',
                                'JOIN_REVIEW_REQUIRED'
            ));
