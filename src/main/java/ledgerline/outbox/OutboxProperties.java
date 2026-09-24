package ledgerline.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("ledgerline.outbox")
public record OutboxProperties(@DefaultValue("ledger.events") String topic,
                               @DefaultValue("3") int partitions,
                               @DefaultValue("100") int batchSize,
                               @DefaultValue("10s") Duration sendTimeout) {}
