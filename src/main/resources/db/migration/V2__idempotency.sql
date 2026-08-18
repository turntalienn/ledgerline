-- Idempotency for POST /transfers.
--
-- The row is inserted in the SAME transaction as the journal entry it protects, so the key and
-- the money movement commit or roll back together. A concurrent duplicate's INSERT ... ON CONFLICT
-- blocks on this primary key until the first transaction finishes, then sees the committed row
-- and replays the stored response. See IdempotencyStore and docs/WALKTHROUGH.md.
CREATE TABLE idempotency_key (
    key              VARCHAR(255) PRIMARY KEY,
    request_hash     CHAR(64)     NOT NULL,      -- sha-256 of the canonical request, to detect key reuse
    response_body    JSONB,                      -- filled before commit; never visible as NULL to others
    journal_entry_id BIGINT       REFERENCES journal_entry(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Belt and braces: even if the idempotency logic had a bug, the database refuses a second
-- journal entry for the same business reference ("transfer:<key>", "payment:<id>").
CREATE UNIQUE INDEX journal_entry_reference_uq ON journal_entry(reference);
