package ledgerline.ledger.balance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param strategy row-lock | optimistic | sharded. A deployment-time choice: restart to switch.
 * @param shards sub-balances per account for the sharded strategy
 * @param retry how hard ConcurrencyRetry tries before answering 409
 */
@ConfigurationProperties("ledgerline.balance")
public record BalanceProperties(
    @DefaultValue("row-lock") String strategy,
    @DefaultValue("8") int shards,
    @DefaultValue Retry retry) {

  public record Retry(
      @DefaultValue("20") int maxAttempts,
      @DefaultValue("2ms") Duration baseBackoff,
      @DefaultValue("100ms") Duration maxBackoff) {}
}
