-- Hot-account strategy (c): split one account's cached balance over N rows.
-- A credit picks a random shard, so N concurrent credits mostly touch different rows.
-- An account's balance = account.balance + SUM(shards). The row-lock and optimistic strategies
-- write the first term, the sharded strategy writes the second, so reads stay correct if you
-- switch strategies between benchmark runs.
-- Shard rows are created on first use (INSERT ... ON CONFLICT DO UPDATE), not up front.
CREATE TABLE account_balance_shard (
    account_id BIGINT NOT NULL REFERENCES account(id),
    shard      INT    NOT NULL,
    balance    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, shard)
);
