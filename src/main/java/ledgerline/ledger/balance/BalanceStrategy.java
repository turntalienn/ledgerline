package ledgerline.ledger.balance;

import java.util.List;

/**
 * How cached balances are updated when an entry is posted. Three implementations, one chosen by
 * ledgerline.balance.strategy, so k6 can compare them on identical traffic:
 *
 * <p>row-lock SELECT ... FOR UPDATE in id order. Simple and correct; a hot account serialises
 * everyone. optimistic @Version check at flush, whole transaction retried on conflict. No waiting,
 * but wasted work and retries explode when one row is very hot. sharded N sub-balance rows per
 * account; credits hit a random one. Spreads a hot receiver, at the price of a SUM on read and
 * serialised debits.
 *
 * <p>Contract: runs inside the caller's transaction, takes locks in a deadlock-free order, and
 * throws InsufficientFundsException if an account that may not go negative would.
 */
public interface BalanceStrategy {

  String name();

  void apply(List<Leg> legs);
}
