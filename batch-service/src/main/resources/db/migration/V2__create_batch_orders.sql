CREATE TABLE batch_orders
(
    id         UUID PRIMARY KEY,

    batch_id   UUID        NOT NULL
        REFERENCES batches (id),

    order_id   UUID        NOT NULL,

    status     VARCHAR(20) NOT NULL
        CHECK (status IN ('ACTIVE', 'REMOVED')),

    joined_at  TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    removed_at TIMESTAMPTZ,

    CONSTRAINT chk_batch_order_removed_at
        CHECK (
            (status = 'ACTIVE' AND removed_at IS NULL)
                OR
            (status = 'REMOVED' AND removed_at IS NOT NULL)
            )
);

CREATE UNIQUE INDEX uq_active_batch_order
    ON batch_orders (order_id) WHERE status = 'ACTIVE';

CREATE INDEX idx_batch_orders_batch_active
    ON batch_orders (batch_id) WHERE status = 'ACTIVE';
