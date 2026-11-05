CREATE TABLE reservations (
    id              UUID         PRIMARY KEY,
    product_id      UUID         NOT NULL REFERENCES products (id),
    user_id         UUID         NOT NULL REFERENCES app_users (id),
    quantity        INTEGER      NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    settled_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,

    -- The idempotency guarantee: one logical reservation per user and key, enforced by PostgreSQL.
    CONSTRAINT uq_reservations_user_idempotency_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_reservations_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_reservations_settled_iff_terminal CHECK ((status = 'PENDING') = (settled_at IS NULL))
);

-- Partial index: the sweeper only ever looks at PENDING rows, which stay a small part of the table.
CREATE INDEX ix_reservations_pending_expiry ON reservations (expires_at) WHERE status = 'PENDING';
CREATE INDEX ix_reservations_user_created ON reservations (user_id, created_at DESC);
CREATE INDEX ix_reservations_product ON reservations (product_id);

-- Last line of defence for the state machine: a settled reservation can never change again,
-- and no update can move a reservation to another product, user or quantity.
CREATE FUNCTION reservations_guard_update() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'reservation % is already % and cannot change', OLD.id, OLD.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_reservations_terminal_state';
    END IF;
    IF (NEW.product_id, NEW.user_id, NEW.quantity) IS DISTINCT FROM (OLD.product_id, OLD.user_id, OLD.quantity) THEN
        RAISE EXCEPTION 'reservation % product, user and quantity are immutable', OLD.id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_reservations_immutable_fields';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_reservations_guard_update
    BEFORE UPDATE ON reservations
    FOR EACH ROW EXECUTE FUNCTION reservations_guard_update();
