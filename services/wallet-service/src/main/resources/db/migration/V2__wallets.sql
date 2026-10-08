CREATE TABLE wallets (
    user_id    UUID PRIMARY KEY,
    balance    BIGINT NOT NULL CHECK (balance >= 0),
    reserved   BIGINT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    version    BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (reserved <= balance)
);

CREATE TABLE ledger_entries (
    id            UUID PRIMARY KEY,
    user_id       UUID NOT NULL REFERENCES wallets(user_id),
    bet_id        UUID NULL,
    type          TEXT NOT NULL CHECK (type IN ('RESERVE', 'DEBIT', 'CREDIT', 'RELEASE')),
    amount        BIGINT NOT NULL CHECK (amount > 0),
    balance_after BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ledger_entries_user_created_idx ON ledger_entries (user_id, created_at DESC);
CREATE INDEX ledger_entries_bet_idx ON ledger_entries (bet_id);
