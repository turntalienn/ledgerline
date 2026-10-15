package ledgerline.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import ledgerline.support.IntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class BalanceProjectionTest extends IntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate tx;

    private void deliver(ConsumerRecord<String, String> record) {
        // Listeners are disabled in this test profile; use the same explicit transaction boundary.
        tx.executeWithoutResult(s -> new BalanceProjectionConsumer(jdbc, json).on(record));
    }
    private ConsumerRecord<String, String> event(UUID id, long a, long b, long amount) {
        var r = new ConsumerRecord<String, String>("test", 0, 0, "entry", """
                {"postings":[{"accountId":%d,"amount":%d},{"accountId":%d,"amount":%d}]}
                """.formatted(a, -amount, b, amount));
        r.headers().add("event_id", id.toString().getBytes(StandardCharsets.UTF_8));
        return r;
    }
    @Test
    void duplicateAndOutOfOrderDeltasAreSafe() {
        long a = user("a").getId(), b = user("b").getId();
        var first = event(UUID.randomUUID(), a, b, 100);
        var later = event(UUID.randomUUID(), b, a, 30);
        deliver(later); deliver(first); deliver(first);
        assertThat(jdbc.queryForObject("select balance from balance_projection where account_id=?", Long.class, b)).isEqualTo(70L);
        assertThat(jdbc.queryForObject("select posting_count from balance_projection where account_id=?", Long.class, b)).isEqualTo(2L);
    }
    @Test
    void failedDeltaRollsBackEarlierAccountUpdatesAndDedupeClaim() {
        long a = user("a").getId(), b = user("b").getId();
        jdbc.update("insert into balance_projection (account_id,balance) values (?,?)", b, Long.MAX_VALUE);
        UUID id = UUID.randomUUID();
        var record = event(id, a, b, 100);
        // a is updated first; b then overflows. Both that first update and the claim must disappear.
        assertThatThrownBy(() -> deliver(record)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select count(*) from balance_projection where account_id=?", Long.class, a)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from processed_event where consumer=? and event_id=?", Long.class,
                BalanceProjectionConsumer.NAME, id)).isZero();
        jdbc.update("update balance_projection set balance=0 where account_id=?", b);
        deliver(record);
        assertThat(jdbc.queryForObject("select balance from balance_projection where account_id=?", Long.class, b)).isEqualTo(100L);
    }
}
