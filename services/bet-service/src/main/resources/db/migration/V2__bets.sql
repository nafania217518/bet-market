CREATE TABLE market_snapshot (
    market_id  UUID PRIMARY KEY,
    status     TEXT        NOT NULL,
    freeze_at  TIMESTAMPTZ NOT NULL,
    min_bet    BIGINT      NOT NULL,
    max_bet    BIGINT      NOT NULL,
    version    BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bets (
    id              UUID PRIMARY KEY,
    market_id       UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    side            TEXT        NOT NULL CHECK (side IN ('TRUE', 'FALSE')),
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    status          TEXT        NOT NULL CHECK (status IN ('ACCEPTED', 'CANCELLED')),
    idempotency_key TEXT        NOT NULL,
    placed_at       TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT bets_user_idempotency_key UNIQUE (user_id, idempotency_key)
);
CREATE INDEX bets_market_status ON bets (market_id, status);
CREATE INDEX bets_user_placed_at ON bets (user_id, placed_at DESC);
