package ledgerline.projection;

import java.time.Instant;
import ledgerline.account.AccountNotFoundException;
import ledgerline.account.AccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class BalanceProjectionController {
    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    public BalanceProjectionController(JdbcTemplate jdbc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }
    public record View(long accountId, long balance, long postingCount, Instant updatedAt, String consistency) {}

    /** A missing projection is not proof of a zero ledger balance: the consumer may not have caught up. */
    @GetMapping("/{id}/projection")
    public View get(@PathVariable long id) {
        if (!accounts.existsById(id)) throw new AccountNotFoundException(id);
        return jdbc.query("select balance, posting_count, updated_at from balance_projection where account_id = ?",
                (rs, n) -> new View(id, rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), "eventual"), id)
                .stream().findFirst().orElse(new View(id, 0, 0, null, "eventual"));
    }
}
