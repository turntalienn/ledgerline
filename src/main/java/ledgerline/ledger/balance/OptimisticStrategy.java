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

/** Strategy (b): optimistic locking with Account.@Version, retried by ConcurrencyRetry. */
@Component
@ConditionalOnProperty(name = "ledgerline.balance.strategy", havingValue = "optimistic")
public class OptimisticStrategy implements BalanceStrategy {

    private final AccountRepository accounts;

    public OptimisticStrategy(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public String name() { return "optimistic"; }

    @Override
    public void apply(List<Leg> legs) {
        // Plain read, no lock. Another transaction may change these rows before we write them back.
        TreeSet<Long> ids = legs.stream().map(Leg::accountId).collect(Collectors.toCollection(TreeSet::new));
        Map<Long, Account> loaded = accounts.findAllById(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        legs.forEach(leg -> loaded.get(leg.accountId()).apply(leg.amount()));
        for (Leg leg : legs) {
            if (!leg.mayGoNegative() && loaded.get(leg.accountId()).getBalance() < 0) {
                throw new InsufficientFundsException(leg.accountId());
            }
        }

        // Flush now, inside the repository proxy, so a stale version surfaces here as Spring's
        // ObjectOptimisticLockingFailureException (a ConcurrencyFailureException) and not later at
        // commit. Hibernate issues UPDATE account ... WHERE id = ? AND version = ?; 0 rows = we lost.
        // hibernate.order_updates=true keeps these UPDATEs in id order, so they cannot deadlock either.
        accounts.saveAllAndFlush(loaded.values());
    }
}
