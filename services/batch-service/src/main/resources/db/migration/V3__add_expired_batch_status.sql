ALTER TABLE batches
DROP CONSTRAINT batches_status_check;

ALTER TABLE batches
    ADD CONSTRAINT batches_status_check
        CHECK (status IN (
                          'OPEN',
                          'CLOSED',
                          'EXPIRED',
                          'DELIVERED'
            ));