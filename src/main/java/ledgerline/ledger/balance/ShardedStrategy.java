package ledgerline.ledger.balance;

import ledgerline.ledger.InsufficientFundsException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Strategy (c): split balances. See V4__balance_shards.sql.
 *
 * Credits (and debits from SYSTEM accounts, which may go negative) never read anything: they add to
 * a random shard row. N concurrent credits to one hot merchant mostly touch different rows.
 *
 * Debits from a USER account must prove the total stays >= 0, which a single shard cannot tell you.
 * So they take the account row lock as a per-account mutex (serialising debits from that account)
 * and then SUM the shards. Credits keep flowing meanwhile and can only make the sum larger, so a
 * slightly stale sum is a safe under-estimate. The trade-off is that this helps a hot RECEIVER, not
 * a hot payer.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.balance.strategy", havingValue = "sharded")
public class ShardedStrategy implements BalanceStrategy {

    private final JdbcTemplate jdbc;
    private final int shards;

    public ShardedStrategy(JdbcTemplate jdbc, BalanceProperties props) {
        this.jdbc = jdbc;
        this.shards = props.shards();
    }

    @Override
    public String name() { return "sharded"; }

    @Override
    public void apply(List<Leg> legs) {
        // Net amount per account, visited in id order: locks are always taken account by account,
        // lowest id first, and within an account the row lock comes before its shard.
        Map<Long, Long> net = new TreeMap<>();
        Map<Long, Boolean> mayGoNegative = new TreeMap<>();
        for (Leg leg : legs) {
            net.merge(leg.accountId(), leg.amount(), Long::sum);
            mayGoNegative.put(leg.accountId(), leg.mayGoNegative());
        }

        net.forEach((accountId, amount) -> {
            if (amount < 0 && !mayGoNegative.get(accountId)) {
                debitWithFundsCheck(accountId, amount);
            } else if (amount != 0) {
                addToShard(accountId, randomShard(), amount);
            }
        });
    }

    private void debitWithFundsCheck(long accountId, long amount) {
        // FOR NO KEY UPDATE, not FOR UPDATE: it still serialises debits, but does not block the
        // FOR KEY SHARE lock that every concurrent posting/shard insert takes on its foreign key.
        Long base = jdbc.queryForObject(
                "select balance from account where id = ? for no key update", Long.class, accountId);
        // A new statement in READ COMMITTED sees everything committed before it, including the
        // previous debit that held the lock we just acquired.
        Long sharded = jdbc.queryForObject(
                "select coalesce(sum(balance), 0) from account_balance_shard where account_id = ?", Long.class, accountId);
        if (base + sharded + amount < 0) {
            throw new InsufficientFundsException(accountId);
        }
        // Individual shards may go negative; only the total has to stay >= 0.
        addToShard(accountId, randomShard(), amount);
    }

    private void addToShard(long accountId, int shard, long amount) {
        jdbc.update("""
                insert into account_balance_shard (account_id, shard, balance) values (?, ?, ?)
                on conflict (account_id, shard) do update set balance = account_balance_shard.balance + excluded.balance
                """, accountId, shard, amount);
    }

    private int randomShard() {
        return ThreadLocalRandom.current().nextInt(shards);
    }
}
