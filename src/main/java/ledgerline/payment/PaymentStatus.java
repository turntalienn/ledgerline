package ledgerline.payment;

/**
 * PENDING   created; the PSP may or may not have charged yet. The only state that can change.
 * SUCCEEDED the PSP charged and the ledger entry is posted. Final.
 * FAILED    the PSP declined, or we gave up. Final. (If the PSP did charge after all, reconciliation finds it.)
 */
public enum PaymentStatus { PENDING, SUCCEEDED, FAILED }
