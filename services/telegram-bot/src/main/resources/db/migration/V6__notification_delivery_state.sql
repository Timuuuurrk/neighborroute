ALTER TABLE bot_notifications
    ADD COLUMN send_started_at TIMESTAMPTZ;

ALTER TABLE bot_notifications
DROP
CONSTRAINT bot_notifications_status_check;

ALTER TABLE bot_notifications
    ADD CONSTRAINT bot_notifications_status_check
        CHECK (status IN (
                          'PENDING',
                          'SENDING',
                          'SENT',
                          'REVIEW_REQUIRED'
            ));

ALTER TABLE bot_notifications
    ADD CONSTRAINT bot_notifications_sent_at_check
        CHECK (
            status <> 'SENT'
                OR sent_at IS NOT NULL
            );
