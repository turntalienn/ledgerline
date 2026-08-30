package ledgerline.ledger.balance;

/** One side of a journal entry as the balance strategies see it. Negative amount = debit. */
public record Leg(long accountId, long amount, boolean mayGoNegative) {}
