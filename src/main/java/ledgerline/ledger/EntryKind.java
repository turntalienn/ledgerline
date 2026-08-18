package ledgerline.ledger;

public enum EntryKind {
    /** Money moving between two accounts inside the ledger. */
    TRANSFER,
    /** Money entering the ledger from the PSP (a pay-in). */
    PAYMENT
}
