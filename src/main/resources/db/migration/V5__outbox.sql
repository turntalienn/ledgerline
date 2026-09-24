-- Transactional outbox. Rows are written in the same transaction as the journal entry, so an
-- event exists if and only if the money moved. OutboxRelay publishes them to Kafka afterwards
-- (at-least-once) and stamps published_at.
CREATE TABLE outbox_event (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,   -- consumers dedupe on this
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,          -- Kafka message key, so one aggregate stays ordered
    event_type     VARCHAR(64)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
-- The relay only ever asks for unpublished rows; a partial index stays small forever.
CREATE INDEX outbox_unpublished_idx ON outbox_event(id) WHERE published_at IS NULL;

-- Idempotent consumers: at-least-once delivery means duplicates WILL arrive.
CREATE TABLE processed_event (
    consumer     VARCHAR(64) NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
