package ledgerline.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sample database queues on a timer, not inside Prometheus scrapes. A slow database must not
 * cause each dashboard refresh to launch another scan. These lab queries need redesign (for
 * example, checkpoint-based lag) before a large deployment; the profile is opt-in.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.observability.enabled", havingValue = "true")
public class DeliveryMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong unprojected = new AtomicLong();
    public DeliveryMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        meters.gauge("ledgerline.outbox.pending", unpublished);
        meters.gauge("ledgerline.projection.pending", unprojected);
    }
    @Scheduled(fixedDelay = 5000)
    public void sample() {
        unpublished.set(jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Long.class));
        unprojected.set(jdbc.queryForObject("""
                select count(*) from outbox_event e where not exists (
                  select 1 from processed_event p where p.consumer='balances-v1' and p.event_id=e.event_id)
                """, Long.class));
    }
}
