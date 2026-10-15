-- A rebuildable read model, NOT the balance used to authorize spending. There is deliberately
-- no FK to account: in a separate consumer service the account table would not be available.
CREATE TABLE balance_projection (
    account_id BIGINT PRIMARY KEY,
    balance BIGINT NOT NULL DEFAULT 0,
    posting_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Reuse processed_event with consumer='balances-v1'. Its key and the deltas commit together.
-- To rebuild: stop this consumer, clear its dedupe rows and projection in ONE transaction,
-- reset ONLY the balances-v1 Kafka group to earliest, then restart. Kafka retention must
-- still contain the complete history; otherwise bootstrap from the journal instead.
