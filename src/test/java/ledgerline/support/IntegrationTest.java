package ledgerline.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import ledgerline.account.Account;
import ledgerline.account.AccountRepository;
import ledgerline.account.AccountType;
import ledgerline.account.BalanceReader;
import ledgerline.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for tests that need the real database. One Postgres container for the whole run (started
 * in a static block, never stopped by JUnit), so Spring's cached contexts never point at a dead
 * container. Skipped, not failed, when no Docker/Podman socket is available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired protected AccountRepository accounts;
    @Autowired protected TransferService transfers;
    @Autowired protected BalanceReader balances;
    @Autowired protected JdbcTemplate jdbc;

    private Account faucet;

    protected Account user(String name) {
        return accounts.save(new Account(name + "-" + UUID.randomUUID(), "INR"));
    }

    /** A USER account with money in it, paid from a SYSTEM faucet like the k6 scripts do. */
    protected Account funded(String name, long amount) {
        Account account = user(name);
        transfers.transfer("fund-" + UUID.randomUUID(), faucet().getId(), account.getId(), amount);
        return account;
    }

    protected Account faucet() {
        if (faucet == null) {
            faucet = accounts.save(new Account("faucet-" + UUID.randomUUID(), "INR", AccountType.SYSTEM));
        }
        return faucet;
    }

    protected int journalEntriesWithReference(String reference) {
        Integer n = jdbc.queryForObject("select count(*) from journal_entry where reference = ?", Integer.class, reference);
        return n == null ? 0 : n;
    }

    /** The database's wall clock. journal_entry.created_at comes from it, so windows must too. */
    protected Instant dbNow() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }
}
