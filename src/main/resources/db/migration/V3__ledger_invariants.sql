-- Account types. USER balances may never go negative; SYSTEM accounts (PSP clearing, fee revenue,
-- a test faucet) are the other side of money entering or leaving the ledger, so they can.
ALTER TABLE account ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'USER'
    CHECK (type IN ('USER', 'SYSTEM'));

-- NOT VALID: enforce for new writes without failing on rows written by the week-1 naive code.
ALTER TABLE account ADD CONSTRAINT account_user_non_negative
    CHECK (type = 'SYSTEM' OR balance >= 0) NOT VALID;

CREATE UNIQUE INDEX account_system_name_uq ON account(name) WHERE type = 'SYSTEM';

INSERT INTO account (name, currency, type) VALUES
    ('psp_clearing', 'INR', 'SYSTEM'),   -- money owed to us by the payment provider
    ('fee_revenue',  'INR', 'SYSTEM');   -- fees we charged on transfers

-- What kind of business event an entry is, and which fee-experiment arm priced it.
ALTER TABLE journal_entry ADD COLUMN kind    VARCHAR(16) NOT NULL DEFAULT 'TRANSFER';
ALTER TABLE journal_entry ADD COLUMN fee_arm VARCHAR(64);
CREATE INDEX journal_entry_created_at_idx ON journal_entry(created_at);

-- Postgres does not index foreign keys for you. The balance trigger below looks postings up
-- by entry, so without this every commit would scan the whole posting table.
CREATE INDEX posting_journal_entry_idx ON posting(journal_entry_id);

-- Double-entry invariant, enforced by the database and not only by JournalEntry.assertBalanced().
-- DEFERRABLE INITIALLY DEFERRED: checked at COMMIT, after all postings of the entry are inserted.
CREATE FUNCTION assert_journal_entry_balanced() RETURNS trigger AS $$
DECLARE
    total BIGINT;
    legs  INT;
BEGIN
    SELECT COALESCE(SUM(amount), 0), COUNT(*) INTO total, legs
    FROM posting WHERE journal_entry_id = NEW.journal_entry_id;
    IF legs < 2 OR total <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced (postings=%, sum=%)',
            NEW.journal_entry_id, legs, total USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER posting_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_entry_balanced();

-- The journal is append-only. Corrections are new, reversing entries.
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER posting_append_only BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_entry_append_only BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
