ALTER TABLE bot_users
    ADD COLUMN draft_product_name VARCHAR(250),
    ADD COLUMN draft_quantity INTEGER,
    ADD COLUMN draft_unit_price NUMERIC(12, 2),
    ADD COLUMN created_order_id UUID;

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
                                'CONFIRMING_ORDER'
            ));

ALTER TABLE bot_users
    ADD CONSTRAINT bot_users_draft_quantity_check
        CHECK (
            draft_quantity IS NULL
                OR draft_quantity BETWEEN 1 AND 1000
            );

ALTER TABLE bot_users
    ADD CONSTRAINT bot_users_draft_price_check
        CHECK (
            draft_unit_price IS NULL
                OR draft_unit_price > 0
            );
