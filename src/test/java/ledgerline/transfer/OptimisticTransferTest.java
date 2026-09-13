package ledgerline.transfer;

import io.micrometer.core.instrument.MeterRegistry;
import ledgerline.account.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "ledgerline.balance.strategy=optimistic")
class OptimisticTransferTest extends TransferContract {

    @Autowired MeterRegistry meters;

    @Override
    String expectedStrategy() { return "optimistic"; }

    /** Proves the contract tests pass because version conflicts were detected and retried, not by luck. */
    @Test
    void contentionShowsUpAsRetries() throws Exception {
        Account from = funded("hot", 1_000_000);
        Account to = user("cold");
        double before = meters.counter("ledgerline.tx.retries", "operation", "transfer").count();

        concurrently(20, i -> () -> transfers.transfer(key(), from.getId(), to.getId(), 1));

        assertThat(meters.counter("ledgerline.tx.retries", "operation", "transfer").count()).isGreaterThan(before);
        assertBalance(to, 20);
    }
}
