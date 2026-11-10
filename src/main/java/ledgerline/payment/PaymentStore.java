package ledgerline.payment;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ledgerline.common.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Plain JDBC: payments are all conditional inserts and guarded state transitions, which read best as SQL. */
@Component
public class PaymentStore {

    private static final RowMapper<Payment> ROW = (rs, i) -> new Payment(
            rs.getLong("id"), rs.getString("idempotency_key"), rs.getString("request_hash"),
            rs.getLong("account_id"), rs.getLong("amount"), PaymentStatus.valueOf(rs.getString("status")),
            rs.getObject("psp_reference", UUID.class), rs.getInt("attempts"), rs.getString("last_error"),
            rs.getObject("journal_entry_id", Long.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public PaymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty if the idempotency key already exists (a concurrent insert of the same key waits for ours). */
    public Optional<Payment> insertIfAbsent(String key, String requestHash, long accountId, long amount, UUID pspReference) {
        return jdbc.query("""
                insert into payment (idempotency_key, request_hash, account_id, amount, status, psp_reference)
                values (?, ?, ?, ?, 'PENDING', ?)
                on conflict (idempotency_key) do nothing
                returning *""", ROW, key, requestHash, accountId, amount, pspReference).stream().findFirst();
    }

    public Payment byKey(String key) {
        return jdbc.query("select * from payment where idempotency_key = ?", ROW, key).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No payment for key " + key));
    }

    public Optional<Payment> byId(long id) {
        return jdbc.query("select * from payment where id = ?", ROW, id).stream().findFirst();
    }

    public Optional<Payment> byPspReference(UUID reference) {
        return jdbc.query("select * from payment where psp_reference = ?", ROW, reference).stream().findFirst();
    }

    /** Serialises everyone trying to settle the same payment: the request thread, webhooks, the resolver. */
    public Payment lockById(long id) {
        return jdbc.query("select * from payment where id = ? for update", ROW, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Payment not found: " + id));
    }

    public void recordAttempt(long id) {
        jdbc.update("update payment set attempts = attempts + 1, updated_at = now() where id = ?", id);
    }

    public void recordError(long id, String error) {
        jdbc.update("update payment set last_error = ?, updated_at = now() where id = ?", error, id);
    }

    public void markSucceeded(long id, long journalEntryId) {
        jdbc.update("""
                update payment set status = 'SUCCEEDED', journal_entry_id = ?, last_error = null, updated_at = now()
                where id = ?""", journalEntryId, id);
    }

    public void markFailed(long id, String reason) {
        jdbc.update("update payment set status = 'FAILED', last_error = ?, updated_at = now() where id = ?", reason, id);
    }

    /** Oldest first. updated_at moves on every attempt, so a payment is retried at most once per olderThan. */
    public List<Payment> pendingOlderThan(Duration olderThan, int limit) {
        return jdbc.query("""
                select * from payment
                where status = 'PENDING' and updated_at < now() - make_interval(secs => ?)
                order by updated_at
                limit ?""", ROW, olderThan.toMillis() / 1000.0, limit);
    }
}
