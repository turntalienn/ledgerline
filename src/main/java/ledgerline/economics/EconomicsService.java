package ledgerline.economics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import ledgerline.account.SystemAccounts;
import ledgerline.fees.FeeArm;
import ledgerline.fees.FeeSchedule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Macro numbers computed straight from the journal (postings + entry timestamps), never from the
 * cached balances. Money supply here means money held by USER accounts.
 */
@Service
public class EconomicsService {

    /**
     * @param p2pVolume  USER-to-USER transfer volume in the window (faucet funding and pay-ins excluded)
     * @param velocity   p2pVolume / average money stock over the window (Fisher: MV = PT, so V = PT / M)
     */
    public record Summary(Instant from, Instant to,
                          long p2pTransfers, long p2pVolume, long payIns, long payInVolume,
                          long moneyStockStart, long moneyStockEnd,
                          double velocity, double velocityAnnualised,
                          int holders, double gini, double hhi, double hhiPoints) {}

    /** @param conversion transfers / quotes shown, null when no quotes were counted for this arm */
    public record ArmResult(String arm, long quotes, long transfers, long payers, long volume,
                            long feeRevenue, double avgFeeBps, Double conversion) {}

    public record FeeExperiment(Instant from, Instant to, boolean enabled, List<ArmResult> arms) {}

    private static final double SECONDS_PER_YEAR = Duration.ofDays(365).toSeconds();

    private final JdbcTemplate jdbc;
    private final FeeSchedule fees;
    private final SystemAccounts systemAccounts;
    private final MeterRegistry meters;

    public EconomicsService(JdbcTemplate jdbc, FeeSchedule fees, SystemAccounts systemAccounts, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.fees = fees;
        this.systemAccounts = systemAccounts;
        this.meters = meters;
    }

    public Summary summary(Instant from, Instant to) {
        long[] startBalances = userBalancesAsOf(from);
        long[] endBalances = userBalancesAsOf(to);
        Concentration end = Concentration.of(endBalances);
        long stockStart = sum(startBalances);
        long stockEnd = end.total();

        Map<String, Object> flows = jdbc.queryForMap("""
                with legs as (
                    select e.id, e.kind,
                           sum(p.amount) filter (where a.type = 'USER' and p.amount > 0) as user_credit,
                           bool_or(a.type = 'USER' and p.amount < 0)                 as user_debit
                    from journal_entry e
                    join posting p on p.journal_entry_id = e.id
                    join account a on a.id = p.account_id
                    where e.created_at >= ? and e.created_at < ?
                    group by e.id, e.kind)
                select count(*) filter (where kind = 'TRANSFER' and user_debit and user_credit is not null) as p2p_count,
                       coalesce(sum(user_credit) filter (where kind = 'TRANSFER' and user_debit), 0)  as p2p_volume,
                       count(*) filter (where kind = 'PAYMENT')                                         as payin_count,
                       coalesce(sum(user_credit) filter (where kind = 'PAYMENT'), 0)                    as payin_volume
                from legs""", Timestamp.from(from), Timestamp.from(to));

        long p2pVolume = ((Number) flows.get("p2p_volume")).longValue();
        double averageStock = (stockStart + stockEnd) / 2.0;
        double velocity = averageStock == 0 ? 0 : p2pVolume / averageStock;
        double windowSeconds = Math.max(1, Duration.between(from, to).toSeconds());

        return new Summary(from, to,
                ((Number) flows.get("p2p_count")).longValue(), p2pVolume,
                ((Number) flows.get("payin_count")).longValue(), ((Number) flows.get("payin_volume")).longValue(),
                stockStart, stockEnd,
                velocity, velocity * SECONDS_PER_YEAR / windowSeconds,
                end.holders(), end.gini(), end.hhi(), end.hhiPoints());
    }

    public FeeExperiment feeExperiment(Instant from, Instant to) {
        long feeAccount = systemAccounts.feeRevenue();
        Map<String, ArmResult> byArm = new LinkedHashMap<>();
        for (FeeArm arm : fees.arms()) {
            byArm.put(arm.name(), new ArmResult(arm.name(), quotes(arm.name()), 0, 0, 0, 0, 0, null));
        }
        jdbc.query("""
                select e.fee_arm,
                       count(distinct e.id)                                                   as transfers,
                       count(distinct p.account_id) filter (where p.amount < 0)               as payers,
                       coalesce(sum(p.amount) filter (where p.amount > 0 and p.account_id <> ?), 0) as volume,
                       coalesce(sum(p.amount) filter (where p.account_id = ?), 0)             as fee_revenue
                from journal_entry e
                join posting p on p.journal_entry_id = e.id
                where e.kind = 'TRANSFER' and e.fee_arm is not null and e.created_at >= ? and e.created_at < ?
                group by e.fee_arm
                order by e.fee_arm""",
                rs -> {
                    String arm = rs.getString("fee_arm");
                    long transfers = rs.getLong("transfers");
                    long volume = rs.getLong("volume");
                    long revenue = rs.getLong("fee_revenue");
                    long quotes = quotes(arm);
                    byArm.put(arm, new ArmResult(arm, quotes, transfers, rs.getLong("payers"), volume, revenue,
                            volume == 0 ? 0 : revenue * 10_000.0 / volume,
                            quotes == 0 ? null : transfers / (double) quotes));
                },
                feeAccount, feeAccount, Timestamp.from(from), Timestamp.from(to));
        return new FeeExperiment(from, to, fees.enabled(), new ArrayList<>(byArm.values()));
    }

    /** USER balances at instant t, from postings of entries created before t. */
    private long[] userBalancesAsOf(Instant t) {
        Timestamp ts = Timestamp.from(t);
        return jdbc.queryForList("""
                select coalesce(sum(p.amount) filter (where e.created_at < ?), 0)
                from account a
                left join posting p on p.account_id = a.id
                left join journal_entry e on e.id = p.journal_entry_id
                where a.type = 'USER' and a.created_at < ?
                group by a.id""", Long.class, ts, ts).stream().mapToLong(Long::longValue).toArray();
    }

    private long quotes(String arm) {
        Counter c = meters.find("ledgerline.fees.quotes").tag("arm", arm).counter();
        return c == null ? 0 : (long) c.count();
    }

    private static long sum(long[] xs) {
        long s = 0;
        for (long x : xs) s += x;
        return s;
    }
}
