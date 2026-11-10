package ledgerline.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxAttempts PSP attempts (each one already retried by Resilience4j) before a payment is FAILED
 */
@ConfigurationProperties("ledgerline.payments")
public record PaymentsProperties(@DefaultValue("8") int maxAttempts, @DefaultValue Resolver resolver) {

    public record Resolver(@DefaultValue("true") boolean enabled,
                           @DefaultValue("15000") long intervalMs,
                           @DefaultValue("10s") Duration olderThan,
                           @DefaultValue("50") int batchSize) {}
}
