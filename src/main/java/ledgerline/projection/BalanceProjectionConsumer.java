package ledgerline.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.UUID;
import ledgerline.ledger.LedgerPoster.LedgerEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Eventually consistent balances built solely from Kafka events. Spending still uses the
 * authoritative ledger: a consumer can lag behind, so this view must never approve a debit.
 *
 * Dedupe and balance deltas share one PostgreSQL transaction. Crash before commit: both roll
 * back and Kafka retries. Crash after commit but before offset commit: Kafka retries, but the
 * event-id insert becomes a no-op. This gives one database effect per event, not exactly-once
 * delivery. Addition commutes, so different journal entries may arrive out of order safely.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.outbox.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class BalanceProjectionConsumer {
    public static final String NAME = "balances-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BalanceProjectionConsumer(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    @KafkaListener(topics = "${ledgerline.outbox.topic:ledger.events}", groupId = NAME,
            containerFactory = "projectionKafkaListenerContainerFactory")
    public void on(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("event_id");
        if (header == null) throw new IllegalArgumentException("Missing event_id");
        UUID id = UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        if (jdbc.update("insert into processed_event (consumer, event_id) values (?, ?) on conflict do nothing",
                NAME, id) == 0) return;

        LedgerEvent event;
        try {
            event = json.readValue(record.value(), LedgerEvent.class);
        } catch (JsonProcessingException e) {
            // Throw, don't swallow: even the dedupe claim must roll back for a repaired retry.
            throw new IllegalArgumentException("Invalid ledger event", e);
        }
        if (event == null || event.postings() == null || event.postings().size() < 2)
            throw new IllegalArgumentException("Ledger event needs at least two postings");
        long sum = 0;
        var amounts = new TreeMap<Long, Long>();
        var counts = new TreeMap<Long, Long>();
        for (var line : event.postings()) {
            if (line == null || line.accountId() <= 0) throw new IllegalArgumentException("Invalid posting");
            sum = Math.addExact(sum, line.amount());
            amounts.merge(line.accountId(), line.amount(), Math::addExact);
            counts.merge(line.accountId(), 1L, Long::sum);
        }
        if (sum != 0) throw new IllegalArgumentException("Unbalanced ledger event");
        // Consistent account order prevents two partitions locking A/B and B/A respectively.
        // Atomic SQL addition also prevents lost updates between concurrent consumers.
        amounts.forEach((accountId, amount) -> jdbc.update("""
                insert into balance_projection (account_id, balance, posting_count) values (?, ?, ?)
                on conflict (account_id) do update
                set balance = balance_projection.balance + excluded.balance,
                    posting_count = balance_projection.posting_count + excluded.posting_count,
                    updated_at = now()
                """, accountId, amount, counts.get(accountId)));
    }
}
