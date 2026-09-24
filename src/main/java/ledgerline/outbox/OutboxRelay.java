package ledgerline.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls outbox_event and publishes unpublished rows to Kafka. Delivery is AT-LEAST-ONCE: if we
 * crash after Kafka acked but before published_at is committed, the batch is sent again on the next
 * poll. Consumers dedupe on the event_id header (see NotificationConsumer).
 *
 * FOR UPDATE SKIP LOCKED lets several app instances run the relay without sending the same row
 * twice at the same time: each one skips rows another instance has already claimed.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    record Row(long id, UUID eventId, String aggregateId, String eventType, String payload) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties props;
    private final Counter published;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                       OutboxProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.props = props;
        this.published = meters.counter("ledgerline.outbox.published");
    }

    @Scheduled(fixedDelayString = "${ledgerline.outbox.relay.poll-interval-ms:500}")
    public void drain() {
        try {
            int sent;
            do {
                sent = publishBatch();
            } while (sent == props.batchSize());
        } catch (RuntimeException e) {
            // Kafka down: rows stay unpublished and the next poll tries again.
            log.warn("Outbox relay could not publish, will retry: {}", e.getMessage());
        }
    }

    public int publishBatch() {
        Integer sent = tx.execute(status -> {
            List<Row> rows = jdbc.query("""
                    select id, event_id, aggregate_id, event_type, payload::text
                    from outbox_event
                    where published_at is null
                    order by id
                    limit ?
                    for update skip locked""",
                    (rs, i) -> new Row(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5)),
                    props.batchSize());
            if (rows.isEmpty()) return 0;

            // Send the whole batch, then wait for every ack. Any failure throws, the transaction
            // rolls back, and none of these rows is marked published.
            CompletableFuture<?>[] acks = rows.stream().map(this::send).toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(acks).get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("Kafka did not ack the outbox batch", e);
            }

            jdbc.batchUpdate("update outbox_event set published_at = now() where id = ?",
                    rows.stream().map(r -> new Object[]{r.id()}).toList());
            return rows.size();
        });
        int count = sent == null ? 0 : sent;
        published.increment(count);
        return count;
    }

    private CompletableFuture<?> send(Row row) {
        // Key = journal-entry id: duplicates land on the same partition. Different entries
        // for the same account can arrive out of order, especially with concurrent relay workers.
        // Consumers must use commutative deltas or explicit versions, not assume account ordering.
        ProducerRecord<String, String> record = new ProducerRecord<>(props.topic(), row.aggregateId(), row.payload());
        record.headers().add("event_id", row.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event_type", row.eventType().getBytes(StandardCharsets.UTF_8));
        return kafka.send(record);
    }

    @Configuration
    @ConditionalOnProperty(name = "ledgerline.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class Topics {
        @Bean
        NewTopic ledgerEventsTopic(OutboxProperties props) {
            return TopicBuilder.name(props.topic()).partitions(props.partitions()).replicas(1).build();
        }
    }
}
