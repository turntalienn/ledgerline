package ledgerline.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ledgerline.account.SystemAccounts;
import ledgerline.common.Hashing;
import ledgerline.fees.FeeQuote;
import ledgerline.fees.FeeSchedule;
import ledgerline.idempotency.IdempotencyStore;
import ledgerline.ledger.ConcurrencyRetry;
import ledgerline.ledger.EntryKind;
import ledgerline.ledger.JournalEntry;
import ledgerline.ledger.LedgerPoster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POST /transfers. One request = one transaction that contains, atomically:
 * the idempotency key, the balance updates, the journal entry, and the outbox event.
 *
 * Transaction guarantees:
 *  1. Idempotency: IdempotencyStore. Same key + same body replays; same key + different body is 422;
 *     concurrent duplicates wait on the key row and then replay.
 *  2. Lost updates: the BalanceStrategy locks (or version-checks) in account-id order.
 *  3. Insufficient funds: USER accounts may not go negative (checked by the strategy, backed by a DB
 *     CHECK). Money enters through SYSTEM accounts: PSP clearing for pay-ins, a faucet for load tests.
 *     A rejected transfer rolls back its key too, so the client can retry it after topping up.
 */
@Service
public class TransferService {

    public record TransferResult(Long journalEntryId, Long fromAccountId, Long toAccountId,
                                 long amount, long fee, String feeArm) {}

    public record Outcome(TransferResult result, boolean replayed) {}

    private final IdempotencyStore idempotency;
    private final LedgerPoster ledger;
    private final FeeSchedule fees;
    private final SystemAccounts systemAccounts;
    private final ConcurrencyRetry retry;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public TransferService(IdempotencyStore idempotency, LedgerPoster ledger, FeeSchedule fees,
                           SystemAccounts systemAccounts, ConcurrencyRetry retry, TransactionTemplate tx,
                           ObjectMapper json) {
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.fees = fees;
        this.systemAccounts = systemAccounts;
        this.retry = retry;
        this.tx = tx;
        this.json = json;
    }

    public Outcome transfer(String idempotencyKey, Long fromId, Long toId, long amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-255 characters");
        }
        if (fromId.equals(toId)) throw new IllegalArgumentException("Cannot transfer to the same account");
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");

        // Hash a canonical form, not the raw JSON, so whitespace or field order cannot cause a false 422.
        String requestHash = Hashing.sha256Hex(fromId + ":" + toId + ":" + amount);

        // The retry wraps the transaction (each attempt is a fresh transaction), never the other way round.
        return retry.run("transfer", () -> tx.execute(status -> post(idempotencyKey, requestHash, fromId, toId, amount)));
    }

    private Outcome post(String key, String requestHash, Long fromId, Long toId, long amount) {
        if (idempotency.claim(key, requestHash) instanceof IdempotencyStore.Replay replay) {
            return new Outcome(read(replay.responseJson()), true);
        }

        FeeQuote quote = fees.quote(fromId, amount);
        JournalEntry entry = new JournalEntry("transfer:" + key, EntryKind.TRANSFER, quote.arm());
        entry.addPosting(fromId, -quote.total());
        entry.addPosting(toId, amount);
        if (quote.fee() > 0) {
            entry.addPosting(systemAccounts.feeRevenue(), quote.fee());
        }
        ledger.post(entry);

        TransferResult result = new TransferResult(entry.getId(), fromId, toId, amount, quote.fee(), quote.arm());
        idempotency.complete(key, entry.getId(), write(result));
        return new Outcome(result, false);
    }

    private String write(TransferResult result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TransferResult read(String body) {
        try {
            return json.readValue(body, TransferResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
