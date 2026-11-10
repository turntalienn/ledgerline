package ledgerline.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a notifications service. The point is the dedupe: the relay is at-least-once, so the
 * same event_id WILL arrive twice sometimes, and the processed_event primary key makes the second
 * one a no-op. If the side effect were a database write (a projection), it would go in the same
 * transaction as the processed_event insert, which makes the effect exactly-once.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.outbox.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationConsumer {

    static final String NAME = "notifications";
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final JdbcTemplate jdbc;
    private final Counter delivered;
    private final Counter duplicates;

    public NotificationConsumer(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.delivered = meters.counter("ledgerline.consumer.delivered", "consumer", NAME);
        this.duplicates = meters.counter("ledgerline.consumer.duplicates", "consumer", NAME);
    }

    @KafkaListener(topics = "${ledgerline.outbox.topic:ledger.events}", groupId = NAME)
    public void on(ConsumerRecord<String, String> record) {
        UUID eventId = UUID.fromString(header(record, "event_id"));
        int fresh = jdbc.update(
                "insert into processed_event (consumer, event_id) values (?, ?) on conflict do nothing", NAME, eventId);
        if (fresh == 0) {
            duplicates.increment();
            log.debug("Skipping duplicate event {}", eventId);
            return;
        }
        delivered.increment();
        log.info("notify: {} key={} {}", header(record, "event_type"), record.key(), record.value());
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        if (h == null) throw new IllegalArgumentException("Missing header " + name);
        return new String(h.value(), StandardCharsets.UTF_8);
    }
}
