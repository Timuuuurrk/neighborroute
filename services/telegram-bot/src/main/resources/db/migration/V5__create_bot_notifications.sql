CREATE TABLE bot_notifications
(
    order_id          UUID        NOT NULL
        REFERENCES bot_orders (order_id),

    notification_type VARCHAR(30)
                                  NOT NULL DEFAULT 'BATCH_DELIVERED',

    event_id          UUID        NOT NULL,

    telegram_user_id  BIGINT      NOT NULL
        REFERENCES bot_users (telegram_user_id),

    batch_id          UUID        NOT NULL,

    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING',

    created_at        TIMESTAMPTZ
                                  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    sent_at           TIMESTAMPTZ,

    PRIMARY KEY (order_id, notification_type),

    CONSTRAINT bot_notifications_type_check
        CHECK (notification_type = 'BATCH_DELIVERED'),

    CONSTRAINT bot_notifications_status_check
        CHECK (status IN ('PENDING', 'SENT'))
);

CREATE INDEX idx_bot_notifications_pending
    ON bot_notifications (status, created_at);
