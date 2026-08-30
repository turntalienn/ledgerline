package ledgerline.ledger;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.OptimisticLockException;
import ledgerline.ledger.balance.BalanceProperties;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Re-runs a WHOLE transaction when it lost a race: an optimistic version check failed, or Postgres
 * picked it as a deadlock victim. It must wrap the transaction, not run inside it, because a
 * transaction that hit one of these errors is already marked rollback-only.
 *
 * Backoff is "full jitter" (sleep a random time up to an exponentially growing cap), so the losers
 * of one round do not all come back at the same instant and collide again.
 */
@Component
public class ConcurrencyRetry {

    private final BalanceProperties.Retry config;
    private final MeterRegistry meters;

    public ConcurrencyRetry(BalanceProperties props, MeterRegistry meters) {
        this.config = props.retry();
        this.meters = meters;
    }

    public <T> T run(String operation, Supplier<T> transaction) {
        return runAttempts(operation, transaction);
    }

    private <T> T runAttempts(String operation, Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (ConcurrencyFailureException | OptimisticLockException e) {
                if (attempt >= config.maxAttempts()) {
                    meters.counter("ledgerline.tx.contention.exhausted", "operation", operation).increment();
                    throw new ContentionException(operation, attempt, e);
                }
                meters.counter("ledgerline.tx.retries", "operation", operation).increment();
                sleep(backoff(attempt));
            }
        }
    }

    private long backoff(int attempt) {
        long cap = Math.min(config.maxBackoff().toMillis(), config.baseBackoff().toMillis() << Math.min(attempt, 16));
        return ThreadLocalRandom.current().nextLong(cap + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }
}
