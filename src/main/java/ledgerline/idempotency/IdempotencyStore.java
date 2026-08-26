package ledgerline.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Idempotency keys stored in Postgres, in the same transaction as the work they protect.
 *
 * claim() does INSERT ... ON CONFLICT DO NOTHING. Three cases:
 *  - nobody has the key:           the insert wins, we do the work, complete() stores the response.
 *  - a finished request has it:    the insert does nothing, we read the stored response and replay it.
 *  - a request is IN FLIGHT with it: Postgres makes our insert WAIT on the primary key until that
 *    transaction ends. If it commits we replay its response; if it rolls back our insert goes
 *    through and we do the work ourselves. Two requests can never both run.
 *
 * No IN_PROGRESS state, no lock expiry and no Redis to keep in sync: the database row lock does it.
 * That only works because a transfer is purely a database operation. Payments call the PSP, which
 * cannot be inside our transaction, so they use a different scheme (see PaymentService).
 */
@Component
public class IdempotencyStore {

    public sealed interface Claim permits Fresh, Replay {}
    public record Fresh() implements Claim {}
    public record Replay(String responseJson) implements Claim {}

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(String key, String requestHash) {
        int inserted = jdbc.update(
                "insert into idempotency_key (key, request_hash) values (?, ?) on conflict (key) do nothing",
                key, requestHash);
        if (inserted == 1) {
            return new Fresh();
        }
        // A new statement in READ COMMITTED sees the row the other transaction just committed.
        Map<String, Object> row = jdbc.queryForMap(
                "select request_hash, response_body::text as body from idempotency_key where key = ?", key);
        if (!requestHash.equals(row.get("request_hash"))) {
            throw new IdempotencyKeyReusedException(key);
        }
        String body = (String) row.get("body");
        if (body == null) {
            throw new IllegalStateException("Idempotency key " + key + " committed without a response");
        }
        return new Replay(body);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String key, long journalEntryId, String responseJson) {
        jdbc.update("update idempotency_key set response_body = ?::jsonb, journal_entry_id = ? where key = ?",
                responseJson, journalEntryId, key);
    }
}
