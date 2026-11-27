CREATE TABLE bot_users
(
    telegram_user_id  BIGINT PRIMARY KEY,

    customer_id       UUID NOT NULL UNIQUE,

    delivery_building VARCHAR(250),
    delivery_details  VARCHAR(250),

    dialog_state      VARCHAR(30)
                           NOT NULL DEFAULT 'IDLE',

    created_at        TIMESTAMPTZ
                           NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT bot_users_dialog_state_check
        CHECK (dialog_state IN (
                                'IDLE',
                                'AWAITING_BUILDING',
                                'AWAITING_DETAILS'
            )),

    CONSTRAINT bot_users_building_not_blank
        CHECK (
            delivery_building IS NULL
                OR LENGTH(TRIM(delivery_building)) > 0
            )
);
