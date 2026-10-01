package ledgerline.payment;

import ledgerline.account.SystemAccounts;
import ledgerline.common.NotFoundException;
import ledgerline.ledger.ConcurrencyRetry;
import ledgerline.ledger.EntryKind;
import ledgerline.ledger.JournalEntry;
import ledgerline.ledger.LedgerPoster;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Drives a payment to a final state. Three things can try to settle the same payment, in any order
 * and any number of times: the original request thread, PSP webhooks (sometimes duplicated), and
 * the resolver job. All of them go through settleSucceeded/settleFailed, which lock the payment row
 * and only act if it is still PENDING. Whoever arrives second sees a final state and does nothing.
 * Exactly-once posting comes from that, plus the unique journal_entry.reference "payment:<id>".
 */
@Component
public class PaymentProcessor {

    private final PaymentStore store;
    private final PspGateway psp;
    private final LedgerPoster ledger;
    private final SystemAccounts systemAccounts;
    private final ConcurrencyRetry retry;
    private final TransactionTemplate tx;
    private final PaymentsProperties props;

    public PaymentProcessor(PaymentStore store, PspGateway psp, LedgerPoster ledger, SystemAccounts systemAccounts,
                            ConcurrencyRetry retry, TransactionTemplate tx, PaymentsProperties props) {
        this.store = store;
        this.psp = psp;
        this.ledger = ledger;
        this.systemAccounts = systemAccounts;
        this.retry = retry;
        this.tx = tx;
        this.props = props;
    }

    /** One attempt at the PSP. Runs OUTSIDE any database transaction: never hold a DB connection across a network call. */
    public Payment attempt(Payment payment) {
        store.recordAttempt(payment.id());
        return switch (psp.charge(payment.pspReference(), payment.amount())) {
            case PspGateway.Charged charged -> settleSucceeded(payment.id());
            case PspGateway.Declined declined -> settleFailed(payment.id(), "declined: " + declined.reason());
            case PspGateway.Unknown unknown -> {
                store.recordError(payment.id(), unknown.reason());
                yield get(payment.id());
            }
        };
    }

    /** Webhook from the PSP. Safe to call any number of times. */
    public Payment onPspNotification(UUID pspReference, String pspStatus) {
        Payment payment = store.byPspReference(pspReference)
                .orElseThrow(() -> new NotFoundException("No payment with PSP reference " + pspReference));
        return "SUCCEEDED".equals(pspStatus)
                ? settleSucceeded(payment.id())
                : settleFailed(payment.id(), "PSP reported " + pspStatus);
    }

    public Payment settleSucceeded(long paymentId) {
        return retry.run("payment", () -> tx.execute(status -> {
            Payment p = store.lockById(paymentId);
            if (p.status() != PaymentStatus.PENDING) {
                return p; // someone else settled it first
            }
            // Money arrives from outside: the PSP owes us (clearing goes negative), the customer is credited.
            JournalEntry entry = new JournalEntry("payment:" + p.id(), EntryKind.PAYMENT);
            entry.addPosting(systemAccounts.pspClearing(), -p.amount());
            entry.addPosting(p.accountId(), p.amount());
            ledger.post(entry);
            store.markSucceeded(p.id(), entry.getId());
            return get(p.id());
        }));
    }

    public Payment settleFailed(long paymentId, String reason) {
        return tx.execute(status -> {
            Payment p = store.lockById(paymentId);
            if (p.status() == PaymentStatus.PENDING) {
                store.markFailed(p.id(), reason);
            }
            return get(p.id());
        });
    }

    /** The resolver's job: retry payments stuck in PENDING (PSP was down, timed out, webhook lost). */
    public int resolvePending(Duration olderThan, int limit) {
        List<Payment> stuck = store.pendingOlderThan(olderThan, limit);
        for (Payment p : stuck) {
            if (p.attempts() >= props.maxAttempts()) {
                // If the PSP did charge in one of those attempts, reconciliation reports MISSING_IN_LEDGER.
                settleFailed(p.id(), "gave up after " + p.attempts() + " attempts; last error: " + p.lastError());
            } else {
                attempt(p);
            }
        }
        return stuck.size();
    }

    public Payment get(long id) {
        return store.byId(id).orElseThrow(() -> new NotFoundException("Payment not found: " + id));
    }
}
