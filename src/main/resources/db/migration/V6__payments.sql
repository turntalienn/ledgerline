-- Pay-ins through an external payment provider (PSP).
-- Unlike a transfer, a payment has a side effect outside our database, so it cannot be one
-- transaction. The payment row is the recovery point: it is committed BEFORE we call the PSP,
-- and psp_reference is the idempotency key we send to the PSP so retries never double-charge.
CREATE TABLE payment (
    id               BIGSERIAL    PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    request_hash     CHAR(64)     NOT NULL,
    account_id       BIGINT       NOT NULL REFERENCES account(id),
    amount           BIGINT       NOT NULL CHECK (amount > 0),
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    psp_reference    UUID         NOT NULL UNIQUE,
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       TEXT,
    journal_entry_id BIGINT       REFERENCES journal_entry(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX payment_pending_idx ON payment(updated_at) WHERE status = 'PENDING';
CREATE INDEX payment_created_at_idx ON payment(created_at);

-- The mock PSP's own books. It lives in this database only for convenience, in its own schema;
-- the ledger never reads it directly, only through the PSP's HTTP API and settlement file.
CREATE SCHEMA mock_psp;
CREATE TABLE mock_psp.charge (
    reference      UUID        PRIMARY KEY,
    amount         BIGINT      NOT NULL,
    status         VARCHAR(16) NOT NULL,        -- SUCCEEDED or DECLINED
    settled_amount BIGINT,                      -- what the settlement file will report
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX mock_psp_charge_created_at_idx ON mock_psp.charge(created_at);
