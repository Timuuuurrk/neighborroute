CREATE TABLE batches
(
    id                UUID PRIMARY KEY,

    store_id          BIGINT       NOT NULL,
    delivery_building VARCHAR(250) NOT NULL,

    status            VARCHAR(20)  NOT NULL
        CHECK (status IN ('OPEN', 'CLOSED', 'DELIVERED')),

    created_at        TIMESTAMPTZ  NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    expires_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT chk_batch_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_batches_matching
    ON batches (
                store_id,
                delivery_building,
                status,
                expires_at
        );
