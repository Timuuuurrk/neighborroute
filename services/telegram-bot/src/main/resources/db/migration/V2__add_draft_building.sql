ALTER TABLE bot_users
    ADD COLUMN draft_building VARCHAR(250);

ALTER TABLE bot_users
    ADD CONSTRAINT bot_users_draft_building_not_blank
        CHECK (
            draft_building IS NULL
                OR LENGTH(TRIM(draft_building)) > 0
            );