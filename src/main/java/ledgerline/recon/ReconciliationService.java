package ledgerline.recon;

import ledgerline.common.NotFoundException;
import ledgerline.payment.PaymentStatus;
import ledgerline.payment.PspGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Daily reconciliation, the job fintechs actually run every morning:
 *
 *  1. PSP settlement file vs our payments for the business day (UTC):
 *     MISSING_IN_LEDGER  the PSP took the money, we never credited the customer (e.g. timed out and gave up)
 *     MISSING_AT_PSP     we credited the customer, the PSP has no settled charge
 *     AMOUNT_MISMATCH    both sides have it, amounts differ (e.g. an unexpected PSP fee)
 *  2. Cached balances vs the journal:
 *     BALANCE_DRIFT      account.balance + shards != SUM(postings). Should never happen; if it does,
 *                        a balance strategy has a bug.
 *
 * Simplification: payments are matched on the day they were created, looking one day either side
 * for the PSP's copy. Real recon also carries unmatched items forward to the next day's file.
 */
@Service
public class ReconciliationService {

    public enum Kind { MISSING_IN_LEDGER, MISSING_AT_PSP, AMOUNT_MISMATCH, BALANCE_DRIFT }

    public record Mismatch(Kind kind, UUID pspReference, Long paymentId, Long accountId, Long ledgerAmount, Long pspAmount) {}

    public record Report(long runId, LocalDate businessDate, int matched, List<Mismatch> mismatches) {}

    private record LedgerPayment(long id, UUID reference, long amount, PaymentStatus status, boolean createdOnDate) {}

    private static final RowMapper<Mismatch> MISMATCH = (rs, i) -> new Mismatch(
            Kind.valueOf(rs.getString("kind")), rs.getObject("psp_reference", UUID.class),
            rs.getObject("payment_id", Long.class), rs.getObject("account_id", Long.class),
            rs.getObject("ledger_amount", Long.class), rs.getObject("psp_amount", Long.class));

    private final PspGateway psp;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReconciliationService(PspGateway psp, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.psp = psp;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Report run(LocalDate date) {
        Map<UUID, Long> settled = parseSettlement(psp.settlementCsv(date));
        Map<UUID, LedgerPayment> ledger = ledgerPayments(date);

        List<Mismatch> mismatches = new ArrayList<>();
        int matched = 0;
        for (Map.Entry<UUID, Long> row : settled.entrySet()) {
            LedgerPayment lp = ledger.get(row.getKey());
            if (lp == null || lp.status() != PaymentStatus.SUCCEEDED) {
                mismatches.add(new Mismatch(Kind.MISSING_IN_LEDGER, row.getKey(), lp == null ? null : lp.id(), null,
                        null, row.getValue()));
            } else if (lp.amount() != row.getValue()) {
                mismatches.add(new Mismatch(Kind.AMOUNT_MISMATCH, row.getKey(), lp.id(), null, lp.amount(), row.getValue()));
            } else {
                matched++;
            }
        }
        for (LedgerPayment lp : ledger.values()) {
            if (lp.createdOnDate() && lp.status() == PaymentStatus.SUCCEEDED && !settled.containsKey(lp.reference())) {
                mismatches.add(new Mismatch(Kind.MISSING_AT_PSP, lp.reference(), lp.id(), null, lp.amount(), null));
            }
        }
        mismatches.addAll(balanceDrift());

        int matchedCount = matched;
        return tx.execute(status -> save(date, matchedCount, mismatches));
    }

    public Report load(long runId) {
        Map<String, Object> run = jdbc.queryForList("select * from recon_run where id = ?", runId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Reconciliation run not found: " + runId));
        List<Mismatch> mismatches = jdbc.query("select * from recon_mismatch where run_id = ? order by id", MISMATCH, runId);
        return new Report(runId, ((java.sql.Date) run.get("business_date")).toLocalDate(), (Integer) run.get("matched"), mismatches);
    }

    private Map<UUID, Long> parseSettlement(String csv) {
        Map<UUID, Long> rows = new LinkedHashMap<>();
        if (csv == null) return rows;
        csv.lines().skip(1).filter(l -> !l.isBlank()).forEach(line -> {
            String[] cols = line.split(",");
            rows.put(UUID.fromString(cols[0].trim()), Long.parseLong(cols[1].trim()));
        });
        return rows;
    }

    private Map<UUID, LedgerPayment> ledgerPayments(LocalDate date) {
        Timestamp dayStart = Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp dayEnd = Timestamp.from(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        Map<UUID, LedgerPayment> byReference = new HashMap<>();
        jdbc.query("""
                select id, psp_reference, amount, status, created_at >= ? and created_at < ? as on_date
                from payment
                where created_at >= ?::timestamptz - interval '1 day' and created_at < ?::timestamptz + interval '1 day'""",
                rs -> {
                    LedgerPayment lp = new LedgerPayment(rs.getLong("id"), rs.getObject("psp_reference", UUID.class),
                            rs.getLong("amount"), PaymentStatus.valueOf(rs.getString("status")), rs.getBoolean("on_date"));
                    byReference.put(lp.reference(), lp);
                },
                dayStart, dayEnd, dayStart, dayEnd);
        return byReference;
    }

    private List<Mismatch> balanceDrift() {
        return jdbc.query("""
                with journal as (select account_id, sum(amount) as total from posting group by account_id),
                     shards  as (select account_id, sum(balance) as total from account_balance_shard group by account_id)
                select a.id, a.balance + coalesce(s.total, 0) as cached, coalesce(j.total, 0) as journal
                from account a
                left join journal j on j.account_id = a.id
                left join shards s on s.account_id = a.id
                where a.balance + coalesce(s.total, 0) <> coalesce(j.total, 0)
                order by a.id""",
                (rs, i) -> new Mismatch(Kind.BALANCE_DRIFT, null, null, rs.getLong("id"), rs.getLong("cached"), rs.getLong("journal")));
    }

    private Report save(LocalDate date, int matched, List<Mismatch> mismatches) {
        Long runId = jdbc.queryForObject(
                "insert into recon_run (business_date, matched, mismatched) values (?, ?, ?) returning id",
                Long.class, date, matched, mismatches.size());
        jdbc.batchUpdate("""
                insert into recon_mismatch (run_id, kind, psp_reference, payment_id, account_id, ledger_amount, psp_amount)
                values (?, ?, ?, ?, ?, ?, ?)""",
                mismatches.stream().map(m -> new Object[]{runId, m.kind().name(), m.pspReference(), m.paymentId(),
                        m.accountId(), m.ledgerAmount(), m.pspAmount()}).toList());
        jdbc.update("update recon_run set finished_at = now() where id = ?", runId);
        return new Report(runId, date, matched, mismatches);
    }
}
