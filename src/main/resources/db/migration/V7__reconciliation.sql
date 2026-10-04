-- Daily reconciliation: the PSP settlement file against our payments, plus a check that cached
-- balances still equal the journal (the source of truth).
CREATE TABLE recon_run (
    id            BIGSERIAL   PRIMARY KEY,
    business_date DATE        NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    matched       INT         NOT NULL DEFAULT 0,
    mismatched    INT         NOT NULL DEFAULT 0
);

CREATE TABLE recon_mismatch (
    id            BIGSERIAL   PRIMARY KEY,
    run_id        BIGINT      NOT NULL REFERENCES recon_run(id),
    kind          VARCHAR(32) NOT NULL,   -- MISSING_IN_LEDGER, MISSING_AT_PSP, AMOUNT_MISMATCH, BALANCE_DRIFT
    psp_reference UUID,
    payment_id    BIGINT,
    account_id    BIGINT,
    ledger_amount BIGINT,
    psp_amount    BIGINT
);
CREATE INDEX recon_mismatch_run_idx ON recon_mismatch(run_id);
