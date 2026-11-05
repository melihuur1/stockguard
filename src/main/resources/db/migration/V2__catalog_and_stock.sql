CREATE TABLE products (
    id          UUID         PRIMARY KEY,
    sku         VARCHAR(64)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    -- Optimistic lock for admin edits. Stock counters live elsewhere so they never bump this.
    version     BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_products_sku UNIQUE (sku)
);

-- Stock is kept apart from the product row: this is the hottest row in the system, and separating it
-- means that a catalogue edit and a reservation never contend for the same row lock.
CREATE TABLE stock_levels (
    product_id         UUID        PRIMARY KEY REFERENCES products (id),
    total_quantity     INTEGER     NOT NULL,
    available_quantity INTEGER     NOT NULL,
    reserved_quantity  INTEGER     NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,

    -- The database itself refuses overselling, even if application code is wrong.
    CONSTRAINT ck_stock_available_non_negative CHECK (available_quantity >= 0),
    CONSTRAINT ck_stock_reserved_non_negative  CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_stock_total_is_sum           CHECK (total_quantity = available_quantity + reserved_quantity)
);
