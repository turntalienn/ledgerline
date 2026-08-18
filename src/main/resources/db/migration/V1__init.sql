-- Accounts hold a cached balance. The postings table is the source of truth;
-- the balance column exists so we can compare hot-account strategies in week 2.
CREATE TABLE account (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    currency    VARCHAR(3)  NOT NULL,
    balance     BIGINT      NOT NULL DEFAULT 0,   -- minor units (paise)
    version     BIGINT      NOT NULL DEFAULT 0,   -- for the optimistic-locking strategy
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One journal entry per business event (a transfer). Immutable.
CREATE TABLE journal_entry (
    id          BIGSERIAL PRIMARY KEY,
    reference   VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Each entry has >= 2 postings whose amounts sum to zero (debit negative, credit positive).
CREATE TABLE posting (
    id               BIGSERIAL PRIMARY KEY,
    journal_entry_id BIGINT NOT NULL REFERENCES journal_entry(id),
    account_id       BIGINT NOT NULL REFERENCES account(id),
    amount           BIGINT NOT NULL CHECK (amount <> 0)
);
CREATE INDEX posting_account_idx ON posting(account_id);

-- TODO (week 1): idempotency_key table.
-- Think about: what's the primary key? What do you store so a retry gets the SAME response?
-- What happens when two requests with the same key arrive at the same millisecond?
