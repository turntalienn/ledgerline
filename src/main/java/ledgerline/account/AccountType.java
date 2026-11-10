package ledgerline.account;

public enum AccountType {
  /** A customer. Balance may never go negative. */
  USER,
  /** The ledger's own books: PSP clearing, fee revenue, a load-test faucet. May go negative. */
  SYSTEM;

  public boolean mayGoNegative() {
    return this == SYSTEM;
  }
}
