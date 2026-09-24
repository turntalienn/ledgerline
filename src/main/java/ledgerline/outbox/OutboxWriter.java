package ledgerline.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes an event row next to the business change, in the same transaction.
 *
 * Why not just call Kafka here? Because "commit to Postgres" and "send to Kafka" are two systems:
 * send first and the commit can fail (a ghost event for money that never moved); commit first and
 * the process can die before the send (money moved, nobody hears about it). Writing the event to
 * the database makes it part of the same commit, and OutboxRelay ships it later.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType, Object payload) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                insert into outbox_event (event_id, aggregate_type, aggregate_id, event_type, payload)
                values (?, ?, ?, ?, ?::jsonb)""",
                eventId, aggregateType, aggregateId, eventType, toJson(payload));
        return eventId;
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
