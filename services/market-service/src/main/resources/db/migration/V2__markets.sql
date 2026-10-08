CREATE TABLE markets (
    id           UUID PRIMARY KEY,
    title        TEXT NOT NULL,
    description  TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('OPEN', 'FROZEN', 'ENDED', 'RESOLVED', 'CANCELLED')),
    winning_side TEXT NULL CHECK (winning_side IN ('TRUE', 'FALSE')),
    freeze_at    TIMESTAMPTZ NOT NULL,
    end_at       TIMESTAMPTZ NOT NULL,
    min_bet      BIGINT NOT NULL,
    max_bet      BIGINT NOT NULL,
    created_by   UUID NOT NULL,
    version      BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (min_bet >= 1 AND max_bet >= min_bet),
    CHECK (end_at > freeze_at)
);
CREATE INDEX markets_status_freeze_at_idx ON markets (status, freeze_at);
CREATE INDEX markets_status_end_at_idx ON markets (status, end_at);
