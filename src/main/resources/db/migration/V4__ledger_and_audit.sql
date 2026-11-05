-- Append-only ledger of every stock change. Each row is inserted while the stock row lock is held, so
-- per product the identity order equals the commit order and the *_after columns form a verifiable chain.
CREATE TABLE stock_movements (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id      UUID         NOT NULL REFERENCES products (id),
    reservation_id  UUID         REFERENCES reservations (id),
    actor_id        UUID         REFERENCES app_users (id),
    type            VARCHAR(24)  NOT NULL,
    delta_total     INTEGER      NOT NULL,
    delta_available INTEGER      NOT NULL,
    delta_reserved  INTEGER      NOT NULL,
    total_after     INTEGER      NOT NULL,
    available_after INTEGER      NOT NULL,
    reserved_after  INTEGER      NOT NULL,
    note            VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_stock_movements_type
        CHECK (type IN ('STOCK_IN', 'RESERVED', 'CONFIRMED', 'CANCEL_RELEASED', 'EXPIRY_RELEASED')),
    CONSTRAINT ck_stock_movements_delta_is_sum CHECK (delta_total = delta_available + delta_reserved),
    CONSTRAINT ck_stock_movements_after_is_sum CHECK (total_after = available_after + reserved_after)
);

CREATE INDEX ix_stock_movements_product ON stock_movements (product_id, id);
CREATE INDEX ix_stock_movements_reservation ON stock_movements (reservation_id);

CREATE TABLE audit_events (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type  VARCHAR(40)  NOT NULL,
    -- NULL actor means the system itself, for example the expiry sweeper.
    actor_id    UUID         REFERENCES app_users (id),
    instance_id VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   UUID         NOT NULL,
    details     JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_events_entity ON audit_events (entity_id, id);

CREATE FUNCTION forbid_history_rewrite() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_append_only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_stock_movements_append_only
    BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION forbid_history_rewrite();

CREATE TRIGGER tg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION forbid_history_rewrite();
