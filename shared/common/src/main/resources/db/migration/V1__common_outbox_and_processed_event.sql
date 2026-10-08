CREATE TABLE outbox_event (
    id           UUID PRIMARY KEY,
    aggregate_id TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ NULL
);
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_event (
    event_id     UUID        NOT NULL,
    consumer     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
