package ledgerline.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import ledgerline.account.Account;
import ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.redpanda.RedpandaContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Outbox -> relay -> Redpanda -> consumer, including a redelivery that the consumer must ignore. */
@TestPropertySource(properties = {
        "ledgerline.outbox.relay.enabled=true",
        "ledgerline.outbox.relay.poll-interval-ms=100",
        "ledgerline.outbox.consumers.enabled=true"
})
class OutboxKafkaTest extends IntegrationTest {

    @ServiceConnection
    static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v25.3.18");

    static {
        REDPANDA.start();
    }

    @Autowired MeterRegistry meters;

    @Test
    void transferEventIsPublishedAndARedeliveryIsDeduplicated() {
        Account from = funded("pub", 1_000);
        Account to = user("sub");
        long entryId = transfers.transfer(key(), from.getId(), to.getId(), 100).result().journalEntryId();

        UUID eventId = jdbc.queryForObject(
                "select event_id from outbox_event where aggregate_id = ? and event_type = 'TransferPosted'",
                UUID.class, String.valueOf(entryId));

        await().atMost(Duration.ofSeconds(30)).until(() -> processed(eventId) == 1);
        assertThat(jdbc.queryForObject("select published_at is not null from outbox_event where event_id = ?", Boolean.class, eventId)).isTrue();

        // Simulate the relay crashing after Kafka acked but before it recorded published_at.
        double duplicatesBefore = meters.counter("ledgerline.consumer.duplicates", "consumer", "notifications").count();
        jdbc.update("update outbox_event set published_at = null where event_id = ?", eventId);

        await().atMost(Duration.ofSeconds(30)).until(() ->
                meters.counter("ledgerline.consumer.duplicates", "consumer", "notifications").count() > duplicatesBefore);
        assertThat(processed(eventId)).as("the second delivery changed nothing").isEqualTo(1);
    }

    private int processed(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from processed_event where consumer = 'notifications' and event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }
}
