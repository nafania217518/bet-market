CREATE TABLE settlement_bet (
    bet_id     UUID PRIMARY KEY,
    market_id  UUID,
    user_id    UUID,
    side       TEXT,
    amount     BIGINT,
    placed_at  TIMESTAMPTZ,
    cancelled  BOOLEAN NOT NULL
);
CREATE INDEX settlement_bet_market_idx ON settlement_bet (market_id);

-- The primary key on market_id is the idempotency guard: one settlement per market.
CREATE TABLE settlements (
    market_id     UUID PRIMARY KEY,
    outcome       TEXT NOT NULL CHECK (outcome IN ('RESOLVED', 'CANCELLED')),
    winning_side  TEXT,
    freeze_at     TIMESTAMPTZ NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('PENDING', 'COMPLETED')),
    true_pool     BIGINT,
    false_pool    BIGINT,
    paid_out      BIGINT,
    house_amount  BIGINT,
    bet_count     INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

-- Audit trail of every payout sent in payout.calculated.
CREATE TABLE settlement_payouts (
    market_id  UUID NOT NULL,
    user_id    UUID NOT NULL,
    amount     BIGINT NOT NULL,
    type       TEXT NOT NULL,
    PRIMARY KEY (market_id, user_id, type)
);
