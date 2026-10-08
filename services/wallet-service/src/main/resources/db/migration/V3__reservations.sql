CREATE TABLE reservations (
    bet_id     UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES wallets(user_id),
    amount     BIGINT NOT NULL CHECK (amount > 0),
    status     TEXT NOT NULL CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED', 'REFUNDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reservations_user_idx ON reservations (user_id);
