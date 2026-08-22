package ledgerline.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-side balances. Never locks. */
@Component
public class BalanceReader {

    private final JdbcTemplate jdbc;

    public BalanceReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cached balance maintained by the configured strategy. */
    public long cachedBalance(long accountId) {
        Long v = jdbc.queryForObject("select balance from account where id = ?", Long.class, accountId);
        return v == null ? 0 : v;
    }

    /** The source of truth: the sum of every posting ever made to the account. */
    public long journalBalance(long accountId) {
        Long v = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from posting where account_id = ?", Long.class, accountId);
        return v == null ? 0 : v;
    }
}
