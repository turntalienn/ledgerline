package ledgerline.ledger;

import ledgerline.account.AccountNotFoundException;
import ledgerline.account.AccountRef;
import ledgerline.account.AccountRepository;
import ledgerline.ledger.balance.BalanceStrategy;
import ledgerline.ledger.balance.Leg;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one way money moves. Transfers and payments both end up here:
 * validate the entry, update cached balances through the configured strategy (which locks),
 * write the journal entry, all in the caller's transaction.
 */
@Component
public class LedgerPoster {

    /** The published event. Its own shape, so internal types can change without breaking consumers. */
    public record LedgerEvent(long journalEntryId, String reference, EntryKind kind, String feeArm,
                              List<Line> postings, Instant occurredAt) {
        public record Line(long accountId, long amount) {}
    }

    private final AccountRepository accounts;
    private final BalanceStrategy balances;
    private final JournalEntryRepository entries;

    public LedgerPoster(AccountRepository accounts, BalanceStrategy balances,
                        JournalEntryRepository entries) {
        this.accounts = accounts;
        this.balances = balances;
        this.entries = entries;
    }

    // MANDATORY: posting outside a transaction would let the balance update, the entry and the
    // commit separately. Fail loudly instead.
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry post(JournalEntry entry) {
        entry.assertBalanced();

        Set<Long> ids = entry.accountIds();
        Map<Long, AccountRef> refs = accounts.findRefs(ids).stream()
                .collect(Collectors.toMap(AccountRef::id, Function.identity()));
        if (refs.size() != ids.size()) {
            Set<Long> missing = new HashSet<>(ids);
            missing.removeAll(refs.keySet());
            throw new AccountNotFoundException(missing);
        }
        if (refs.values().stream().map(AccountRef::currency).distinct().count() > 1) {
            throw new IllegalArgumentException("Cross-currency entries are not supported (no FX in v1)");
        }

        List<Leg> legs = entry.getPostings().stream()
                .map(p -> new Leg(p.getAccountId(), p.getAmount(), refs.get(p.getAccountId()).type().mayGoNegative()))
                .toList();
        balances.apply(legs);

        entries.save(entry);

        return entry;
    }
}
