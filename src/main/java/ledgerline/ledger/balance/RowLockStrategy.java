package ledgerline.ledger.balance;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import ledgerline.account.Account;
import ledgerline.account.AccountRepository;
import ledgerline.ledger.InsufficientFundsException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Strategy (a): pessimistic row locks. The default. */
@Component
@ConditionalOnProperty(name = "ledgerline.balance.strategy", havingValue = "row-lock", matchIfMissing = true)
public class RowLockStrategy implements BalanceStrategy {

    private final AccountRepository accounts;

    public RowLockStrategy(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public String name() { return "row-lock"; }

    @Override
    public void apply(List<Leg> legs) {
        // Lowest id first, always. Two transfers A->B and B->A both lock A then B, so neither can
        // hold one row while waiting for the other.
        TreeSet<Long> ids = legs.stream().map(Leg::accountId).collect(Collectors.toCollection(TreeSet::new));
        Map<Long, Account> locked = accounts.lockAllInIdOrder(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        legs.forEach(leg -> locked.get(leg.accountId()).apply(leg.amount()));

        // Check after applying every leg, in case one account appears on both sides.
        for (Leg leg : legs) {
            if (!leg.mayGoNegative() && locked.get(leg.accountId()).getBalance() < 0) {
                throw new InsufficientFundsException(leg.accountId());
            }
        }
        // No save() needed: the accounts are managed entities, and Hibernate flushes the dirty
        // rows at commit while we still hold the locks.
    }
}
