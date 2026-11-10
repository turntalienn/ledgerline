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

  /** Cached balance: the account row plus any shard rows (see V4__balance_shards.sql). */
  public long cachedBalance(long accountId) {
    Long v =
        jdbc.queryForObject(
            """
            select a.balance + coalesce((select sum(s.balance) from account_balance_shard s
                                         where s.account_id = a.id), 0)
            from account a where a.id = ?\
            """,
            Long.class,
            accountId);
    return v == null ? 0 : v;
  }

  /** The source of truth: the sum of every posting ever made to the account. */
  public long journalBalance(long accountId) {
    Long v =
        jdbc.queryForObject(
            "select coalesce(sum(amount), 0) from posting where account_id = ?",
            Long.class,
            accountId);
    return v == null ? 0 : v;
  }
}
