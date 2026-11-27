CREATE TABLE inbox_events
(
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    aggregate_id UUID         NOT NULL,
    payload      JSONB        NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);