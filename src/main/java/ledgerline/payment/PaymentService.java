package ledgerline.payment;

import ledgerline.account.AccountNotFoundException;
import ledgerline.account.AccountRef;
import ledgerline.account.AccountRepository;
import ledgerline.account.AccountType;
import ledgerline.account.SystemAccounts;
import ledgerline.common.Hashing;
import ledgerline.idempotency.IdempotencyKeyReusedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * POST /payments: a pay-in from the PSP to a customer account.
 *
 * Idempotency works differently from transfers. A transfer is one DB transaction, so the key can
 * commit together with the result. A payment calls the PSP in the middle, and a network call cannot
 * be inside a DB transaction. So:
 *   1. INSERT the payment row (PENDING, with a fresh psp_reference) and COMMIT it. This is the
 *      recovery point: from now on the resolver knows the payment exists, even if we crash.
 *   2. Call the PSP with psp_reference as ITS idempotency key, outside any transaction.
 *   3. Settle the outcome in a short new transaction.
 * A duplicate request with the same key finds the row from step 1 and gets the payment's current
 * state back. It never calls the PSP itself.
 */
@Service
public class PaymentService {

    public record Outcome(Payment payment, boolean replayed) {}

    private final PaymentStore store;
    private final PaymentProcessor processor;
    private final AccountRepository accounts;
    private final SystemAccounts systemAccounts;

    public PaymentService(PaymentStore store, PaymentProcessor processor, AccountRepository accounts,
                          SystemAccounts systemAccounts) {
        this.store = store;
        this.processor = processor;
        this.accounts = accounts;
        this.systemAccounts = systemAccounts;
    }

    public Outcome create(String idempotencyKey, long accountId, long amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-255 characters");
        }
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        validateBeforeCharging(accountId);

        String requestHash = Hashing.sha256Hex(accountId + ":" + amount);
        // Single statement, auto-committed: the row must be durable BEFORE the PSP is called.
        Optional<Payment> created = store.insertIfAbsent(idempotencyKey, requestHash, accountId, amount, UUID.randomUUID());
        if (created.isEmpty()) {
            Payment existing = store.byKey(idempotencyKey);
            if (!existing.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return new Outcome(existing, true);
        }
        return new Outcome(processor.attempt(created.get()), false);
    }

    /** Anything that would make the ledger refuse the entry must be caught before the customer is charged. */
    private void validateBeforeCharging(long accountId) {
        long clearing = systemAccounts.pspClearing();
        Map<Long, AccountRef> refs = accounts.findRefs(List.of(accountId, clearing)).stream()
                .collect(Collectors.toMap(AccountRef::id, Function.identity()));
        AccountRef target = refs.get(accountId);
        if (target == null) throw new AccountNotFoundException(accountId);
        if (target.type() != AccountType.USER) throw new IllegalArgumentException("Pay-ins go to USER accounts");
        if (!target.currency().equals(refs.get(clearing).currency())) {
            throw new IllegalArgumentException("The PSP only settles " + refs.get(clearing).currency());
        }
    }
}
